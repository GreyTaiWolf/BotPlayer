package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationRequest;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationToken;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Server-owner-thread adapter which leases a construction binding's conservative spatial tiles.
 *
 * <p>The underlying {@link ResourceReservationService} remains the sole authority for capacity,
 * TTL, expiration, release-by-run, and release-by-generation. This adapter only maps one exact
 * immutable {@link ConstructionSiteBinding} to a bounded set of exclusive {@code WORK_AREA} keys
 * and lazily forgets cached leases whose underlying tokens no longer exist. It never accesses a
 * Minecraft world, creates a material reservation, or authorizes an Action, Technique, Skill, or
 * placement.
 */
public final class ConstructionSiteLeaseService {
    /** A Blueprint bound spans no more than 32 blocks on any axis. */
    public static final int TILE_EDGE = Blueprint.MAX_AXIS_SPAN;
    /** An inclusive 32-block bound crosses at most two fixed tiles per axis. */
    public static final int MAX_TILES_PER_BINDING = 8;

    private static final String TILE_SUBJECT_PREFIX = "construction/tile/";

    private final Thread ownerThread;
    private final ResourceReservationService reservations;
    private final Map<LeaseOwner, ConstructionSiteLease> leasesByOwner =
            new LinkedHashMap<>();
    private long lastObservedTick = -1L;

    public ConstructionSiteLeaseService(ResourceReservationService reservations) {
        this.ownerThread = Thread.currentThread();
        this.reservations = Objects.requireNonNull(reservations, "reservations");
    }

    /**
     * Atomically acquires every conservative spatial tile for one exact binding.
     *
     * <p>One {@code (botId, generation, skillRunId)} may hold only one live binding through this
     * adapter. Repeating that exact binding returns the original opaque lease; attempting to reuse
     * its identity for a different site/plan/anchor/bounds fails closed before touching the shared
     * reservation table.
     */
    public AcquireResult acquire(UUID botId, long botGeneration, UUID skillRunId,
            ConstructionSiteBinding binding, long currentTick, int leaseTicks) {
        requireOwnerThread();
        observeTick(currentTick);
        UUID checkedBotId = requireNonZero(botId, "botId");
        requirePositiveGeneration(botGeneration);
        UUID checkedSkillRunId = requireNonZero(skillRunId, "skillRunId");
        ConstructionSiteBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        if (!fitsReservationScope(checkedBinding.anchor().dimension())) {
            return AcquireResult.rejected(AcquireStatus.DIMENSION_KEY_TOO_LONG);
        }

        reapStaleLeases(currentTick);
        LeaseOwner owner = new LeaseOwner(checkedBotId, botGeneration, checkedSkillRunId);
        ConstructionSiteLease existing = leasesByOwner.get(owner);
        if (existing != null) {
            return existing.hasExactBinding(checkedBinding)
                    ? new AcquireResult(AcquireStatus.ALREADY_HELD, Optional.of(existing))
                    : AcquireResult.rejected(AcquireStatus.BINDING_MISMATCH);
        }

        List<ReservationKey> keys = tileKeysFor(checkedBinding);
        ResourceReservationService.AcquireAllResult result = reservations.acquireAll(
                checkedBotId,
                botGeneration,
                checkedSkillRunId,
                keys.stream()
                        .map(key -> new ReservationRequest(key, ReservationMode.EXCLUSIVE))
                        .toList(),
                currentTick,
                leaseTicks);
        if (!result.acquired()) {
            return AcquireResult.rejected(mapStatus(result.status()));
        }

        List<ResourceReservationService.AcquireAllEntry> entries = result.entries();
        if (!matchesFreshExclusiveTileAcquisition(entries, keys)) {
            rollbackNewEntries(entries);
            return AcquireResult.rejected(AcquireStatus.RESERVATION_STATE_UNTRACKED);
        }

        List<ReservationToken> tokens = entries.stream()
                .map(ResourceReservationService.AcquireAllEntry::token)
                .toList();
        ConstructionSiteLease lease;
        try {
            lease = new ConstructionSiteLease(checkedBotId, botGeneration, checkedSkillRunId,
                    checkedBinding, tokens);
        } catch (RuntimeException exception) {
            rollbackTokens(tokens);
            throw exception;
        }
        leasesByOwner.put(owner, lease);
        return new AcquireResult(AcquireStatus.ACQUIRED, Optional.of(lease));
    }

    /**
     * Returns true only while this exact opaque lease, its complete immutable binding, and every
     * underlying shared-resource token are still current.
     */
    public boolean isCurrent(ConstructionSiteLease lease,
            ConstructionSiteBinding binding, long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        ConstructionSiteLease checkedLease = Objects.requireNonNull(lease, "lease");
        ConstructionSiteBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        reapStaleLeases(currentTick);
        LeaseOwner owner = LeaseOwner.from(checkedLease);
        return leasesByOwner.get(owner) == checkedLease
                && checkedLease.hasExactBinding(checkedBinding);
    }

    /**
     * Renews one current opaque lease only for its complete value-equal binding.
     *
     * <p>Every underlying token is renewed by the existing reservation authority, then this
     * adapter publishes one replacement opaque lease. The supplied lease is immediately stale on
     * success. Foreign, stale, or binding-drifted leases are rejected before any raw renewal is
     * attempted. An out-of-band raw renewal similarly makes the cached token set stale, so this
     * adapter will not adopt or renew that replacement without a new tracked acquisition.
     */
    public RenewResult renew(ConstructionSiteLease lease,
            ConstructionSiteBinding binding, long currentTick, int leaseTicks) {
        requireOwnerThread();
        observeTick(currentTick);
        ConstructionSiteLease checkedLease = Objects.requireNonNull(lease, "lease");
        ConstructionSiteBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        reapStaleLeases(currentTick);
        LeaseOwner owner = LeaseOwner.from(checkedLease);
        if (leasesByOwner.get(owner) != checkedLease) {
            return RenewResult.rejected(RenewStatus.FOREIGN_OR_STALE);
        }
        if (!checkedLease.hasExactBinding(checkedBinding)) {
            return RenewResult.rejected(RenewStatus.BINDING_MISMATCH);
        }

        List<ReservationToken> replacementTokens = new ArrayList<>(checkedLease.tileCount());
        try {
            for (ReservationToken token : checkedLease.tokens()) {
                ResourceReservationService.RenewResult result = reservations.renew(
                        token, currentTick, leaseTicks);
                if (result.status() != ResourceReservationService.RenewStatus.RENEWED) {
                    invalidate(owner, checkedLease);
                    return RenewResult.rejected(RenewStatus.RESERVATION_STATE_UNTRACKED);
                }
                replacementTokens.add(result.token().orElseThrow(() -> new IllegalStateException(
                        "renewed reservation token was unexpectedly absent")));
            }

            ConstructionSiteLease replacement = new ConstructionSiteLease(
                    checkedLease.botId(), checkedLease.botGeneration(), checkedLease.skillRunId(),
                    checkedBinding, replacementTokens);
            if (!leasesByOwner.replace(owner, checkedLease, replacement)) {
                invalidate(owner, checkedLease);
                return RenewResult.rejected(RenewStatus.RESERVATION_STATE_UNTRACKED);
            }
            return new RenewResult(RenewStatus.RENEWED, Optional.of(replacement));
        } catch (RuntimeException exception) {
            if (!replacementTokens.isEmpty() || exception instanceof IllegalStateException) {
                invalidate(owner, checkedLease);
            }
            throw exception;
        }
    }

    /**
     * Releases all tiles held by one current opaque lease. A foreign, expired, externally released,
     * or already-released lease is never forwarded as an arbitrary raw reservation-token release.
     */
    public ReleaseStatus release(ConstructionSiteLease lease, long currentTick) {
        requireOwnerThread();
        observeTick(currentTick);
        ConstructionSiteLease checkedLease = Objects.requireNonNull(lease, "lease");
        reapStaleLeases(currentTick);
        LeaseOwner owner = LeaseOwner.from(checkedLease);
        if (leasesByOwner.get(owner) != checkedLease) {
            return ReleaseStatus.FOREIGN_OR_STALE;
        }
        for (ReservationToken token : checkedLease.tokens()) {
            ResourceReservationService.ReleaseStatus status = reservations.release(token);
            if (status != ResourceReservationService.ReleaseStatus.RELEASED) {
                leasesByOwner.remove(owner, checkedLease);
                return ReleaseStatus.FOREIGN_OR_STALE;
            }
        }
        leasesByOwner.remove(owner, checkedLease);
        return ReleaseStatus.RELEASED;
    }

    /**
     * Returns the conservative fixed-tile keys for one binding. Package visibility exists solely for
     * same-package contract tests; callers must acquire through {@link #acquire} instead of using
     * these keys as an authorization shortcut.
     */
    static List<ReservationKey> tileKeysFor(ConstructionSiteBinding binding) {
        ConstructionSiteBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        ResourceId dimension = checkedBinding.anchor().dimension();
        if (!fitsReservationScope(dimension)) {
            throw new IllegalArgumentException("construction dimension does not fit reservation scope");
        }
        ConstructionSiteBounds bounds = checkedBinding.constructionBounds();
        int minimumTileX = Math.floorDiv(bounds.minimum().x(), TILE_EDGE);
        int maximumTileX = Math.floorDiv(bounds.maximum().x(), TILE_EDGE);
        int minimumTileY = Math.floorDiv(bounds.minimum().y(), TILE_EDGE);
        int maximumTileY = Math.floorDiv(bounds.maximum().y(), TILE_EDGE);
        int minimumTileZ = Math.floorDiv(bounds.minimum().z(), TILE_EDGE);
        int maximumTileZ = Math.floorDiv(bounds.maximum().z(), TILE_EDGE);

        List<ReservationKey> keys = new ArrayList<>(MAX_TILES_PER_BINDING);
        for (int tileX = minimumTileX; ; tileX++) {
            for (int tileY = minimumTileY; ; tileY++) {
                for (int tileZ = minimumTileZ; ; tileZ++) {
                    keys.add(tileKey(dimension, tileX, tileY, tileZ));
                    if (tileZ == maximumTileZ) {
                        break;
                    }
                }
                if (tileY == maximumTileY) {
                    break;
                }
            }
            if (tileX == maximumTileX) {
                break;
            }
        }
        if (keys.isEmpty() || keys.size() > MAX_TILES_PER_BINDING) {
            throw new IllegalStateException("construction binding exceeded its fixed tile budget");
        }
        return List.copyOf(keys);
    }

    private void reapStaleLeases(long currentTick) {
        List<LeaseOwner> staleOwners = new ArrayList<>();
        for (Map.Entry<LeaseOwner, ConstructionSiteLease> entry : leasesByOwner.entrySet()) {
            if (!allTokensCurrent(entry.getValue(), currentTick)) {
                staleOwners.add(entry.getKey());
            }
        }
        staleOwners.forEach(owner -> leasesByOwner.remove(owner));
    }

    private boolean allTokensCurrent(ConstructionSiteLease lease, long currentTick) {
        for (ReservationToken token : lease.tokens()) {
            if (!reservations.inspect(token.key(), currentTick).contains(token)) {
                return false;
            }
        }
        return true;
    }

    private void invalidate(LeaseOwner owner, ConstructionSiteLease lease) {
        leasesByOwner.remove(owner, lease);
    }

    private static boolean matchesFreshExclusiveTileAcquisition(
            List<ResourceReservationService.AcquireAllEntry> entries,
            List<ReservationKey> expectedKeys) {
        if (entries.size() != expectedKeys.size()) {
            return false;
        }
        Set<ReservationKey> expected = new HashSet<>(expectedKeys);
        Set<ReservationKey> actual = new HashSet<>();
        for (ResourceReservationService.AcquireAllEntry entry : entries) {
            if (entry.status() != ResourceReservationService.AcquireStatus.ACQUIRED
                    || entry.token().mode() != ReservationMode.EXCLUSIVE
                    || entry.token().key().kind() != ReservationKey.Kind.WORK_AREA
                    || !actual.add(entry.key())) {
                return false;
            }
        }
        return actual.equals(expected);
    }

    private void rollbackNewEntries(
            List<ResourceReservationService.AcquireAllEntry> entries) {
        List<ReservationToken> acquired = entries.stream()
                .filter(entry -> entry.status()
                        == ResourceReservationService.AcquireStatus.ACQUIRED)
                .map(ResourceReservationService.AcquireAllEntry::token)
                .toList();
        rollbackTokens(acquired);
    }

    private void rollbackTokens(List<ReservationToken> tokens) {
        for (ReservationToken token : tokens) {
            if (reservations.release(token)
                    != ResourceReservationService.ReleaseStatus.RELEASED) {
                throw new IllegalStateException(
                        "construction site lease rollback could not release a fresh tile");
            }
        }
    }

    private static AcquireStatus mapStatus(ResourceReservationService.AcquireAllStatus status) {
        return switch (Objects.requireNonNull(status, "status")) {
            case CONFLICT -> AcquireStatus.CONFLICT;
            case CAPACITY_EXHAUSTED -> AcquireStatus.CAPACITY_EXHAUSTED;
            case ID_UNAVAILABLE -> AcquireStatus.ID_UNAVAILABLE;
            case ACQUIRED, ALREADY_HELD, EMPTY_REQUEST, DUPLICATE_KEY ->
                    throw new IllegalStateException("unexpected construction tile acquisition status: "
                            + status);
        };
    }

    private static ReservationKey tileKey(ResourceId dimension,
            int tileX, int tileY, int tileZ) {
        return new ReservationKey(ReservationKey.Kind.WORK_AREA,
                dimension.value(),
                TILE_SUBJECT_PREFIX + tileX + "/" + tileY + "/" + tileZ);
    }

    private static boolean fitsReservationScope(ResourceId dimension) {
        return Objects.requireNonNull(dimension, "dimension").value().length()
                <= ReservationKey.MAX_SCOPE_LENGTH;
    }

    private void observeTick(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        if (currentTick < lastObservedTick) {
            throw new IllegalArgumentException("currentTick must not move backwards");
        }
        lastObservedTick = currentTick;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Construction site leases require the owner server thread");
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (required.getMostSignificantBits() == 0L
                && required.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
        return required;
    }

    private static void requirePositiveGeneration(long botGeneration) {
        if (botGeneration < 1L) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
    }

    private record LeaseOwner(UUID botId, long botGeneration, UUID skillRunId) {
        private LeaseOwner {
            requireNonZero(botId, "botId");
            requirePositiveGeneration(botGeneration);
            requireNonZero(skillRunId, "skillRunId");
        }

        static LeaseOwner from(ConstructionSiteLease lease) {
            ConstructionSiteLease checkedLease = Objects.requireNonNull(lease, "lease");
            return new LeaseOwner(checkedLease.botId(), checkedLease.botGeneration(),
                    checkedLease.skillRunId());
        }
    }

    public enum AcquireStatus {
        ACQUIRED,
        ALREADY_HELD,
        BINDING_MISMATCH,
        DIMENSION_KEY_TOO_LONG,
        CONFLICT,
        CAPACITY_EXHAUSTED,
        ID_UNAVAILABLE,
        RESERVATION_STATE_UNTRACKED
    }

    /** Successful statuses carry the only lease instances this service considers current. */
    public record AcquireResult(AcquireStatus status, Optional<ConstructionSiteLease> lease) {
        public AcquireResult {
            status = Objects.requireNonNull(status, "status");
            lease = Objects.requireNonNull(lease, "lease");
            boolean success = status == AcquireStatus.ACQUIRED
                    || status == AcquireStatus.ALREADY_HELD;
            if (lease.isPresent() != success) {
                throw new IllegalArgumentException(
                        "construction site lease result did not match its status");
            }
        }

        public boolean acquired() {
            return status == AcquireStatus.ACQUIRED
                    || status == AcquireStatus.ALREADY_HELD;
        }

        private static AcquireResult rejected(AcquireStatus status) {
            return new AcquireResult(status, Optional.empty());
        }
    }

    public enum ReleaseStatus {
        RELEASED,
        FOREIGN_OR_STALE
    }

    public enum RenewStatus {
        RENEWED,
        BINDING_MISMATCH,
        FOREIGN_OR_STALE,
        RESERVATION_STATE_UNTRACKED
    }

    /** Successful renewal returns the replacement lease; the supplied lease is no longer current. */
    public record RenewResult(RenewStatus status, Optional<ConstructionSiteLease> lease) {
        public RenewResult {
            status = Objects.requireNonNull(status, "status");
            lease = Objects.requireNonNull(lease, "lease");
            if (lease.isPresent() != (status == RenewStatus.RENEWED)) {
                throw new IllegalArgumentException(
                        "construction site lease renewal result did not match its status");
            }
        }

        public boolean renewed() {
            return status == RenewStatus.RENEWED;
        }

        private static RenewResult rejected(RenewStatus status) {
            return new RenewResult(status, Optional.empty());
        }
    }
}
