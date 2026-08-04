package io.github.greytaiwolf.botplayer.skill.reservation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 服务器主线程上的有界、带 TTL 的资源租约表。
 */
public final class ResourceReservationService {
    public static final int MAXIMUM_CAPACITY = 65_536;
    public static final int MAXIMUM_LEASE_TICKS = 72_000;
    private static final int MAXIMUM_ID_ATTEMPTS = 8;
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private final Thread ownerThread;
    private final int capacity;
    private final int maximumLeaseTicks;
    private final Supplier<UUID> idSupplier;
    private final Map<UUID, ReservationToken> leasesById =
            new LinkedHashMap<>();
    private final Map<ReservationKey, List<ReservationToken>> leasesByKey =
            new LinkedHashMap<>();
    private long lastObservedTick = -1L;

    public ResourceReservationService(
            int capacity, int maximumLeaseTicks) {
        this(
                capacity,
                maximumLeaseTicks,
                UUID::randomUUID);
    }

    ResourceReservationService(
            int capacity,
            int maximumLeaseTicks,
            Supplier<UUID> idSupplier) {
        if (capacity < 1 || capacity > MAXIMUM_CAPACITY) {
            throw new IllegalArgumentException(
                    "capacity must be between 1 and "
                            + MAXIMUM_CAPACITY);
        }
        if (maximumLeaseTicks < 1
                || maximumLeaseTicks > MAXIMUM_LEASE_TICKS) {
            throw new IllegalArgumentException(
                    "maximumLeaseTicks must be between 1 and "
                            + MAXIMUM_LEASE_TICKS);
        }
        this.ownerThread = Thread.currentThread();
        this.capacity = capacity;
        this.maximumLeaseTicks = maximumLeaseTicks;
        this.idSupplier = Objects.requireNonNull(
                idSupplier, "idSupplier");
    }

    public AcquireResult acquire(
            UUID botId,
            long botGeneration,
            UUID skillRunId,
            ReservationKey key,
            ReservationMode mode,
            long currentTick,
            int leaseTicks) {
        requireOwnerThread();
        requireIdentity(botId, botGeneration, skillRunId);
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(mode, "mode");
        validateLeaseTicks(leaseTicks);
        expireAt(observeTick(currentTick));

        List<ReservationToken> existing =
                leasesByKey.getOrDefault(key, List.of());
        for (ReservationToken token : existing) {
            if (sameOwner(
                    token, botId, botGeneration, skillRunId)) {
                return token.mode() == mode
                        ? new AcquireResult(
                                AcquireStatus.ALREADY_HELD,
                                Optional.of(token))
                        : AcquireResult.rejected(
                                AcquireStatus.CONFLICT);
            }
        }
        boolean conflicts = existing.stream().anyMatch(token ->
                mode == ReservationMode.EXCLUSIVE
                        || token.mode()
                                == ReservationMode.EXCLUSIVE);
        if (conflicts) {
            return AcquireResult.rejected(
                    AcquireStatus.CONFLICT);
        }
        if (leasesById.size() >= capacity) {
            return AcquireResult.rejected(
                    AcquireStatus.CAPACITY_EXHAUSTED);
        }

        UUID reservationId = nextId();
        if (reservationId == null) {
            return AcquireResult.rejected(
                    AcquireStatus.ID_UNAVAILABLE);
        }
        ReservationToken token = new ReservationToken(
                reservationId,
                botId,
                botGeneration,
                skillRunId,
                key,
                mode,
                currentTick,
                Math.addExact(currentTick, leaseTicks));
        leasesById.put(reservationId, token);
        leasesByKey
                .computeIfAbsent(
                        key, ignored -> new ArrayList<>())
                .add(token);
        return new AcquireResult(
                AcquireStatus.ACQUIRED, Optional.of(token));
    }

    public RenewResult renew(
            ReservationToken token,
            long currentTick,
            int leaseTicks) {
        requireOwnerThread();
        Objects.requireNonNull(token, "token");
        validateLeaseTicks(leaseTicks);
        expireAt(observeTick(currentTick));
        ReservationToken current =
                leasesById.get(token.reservationId());
        if (current == null) {
            return RenewResult.rejected(
                    RenewStatus.NOT_FOUND);
        }
        if (!current.equals(token)) {
            return RenewResult.rejected(
                    RenewStatus.STALE_TOKEN);
        }

        ReservationToken renewed = new ReservationToken(
                current.reservationId(),
                current.botId(),
                current.botGeneration(),
                current.skillRunId(),
                current.key(),
                current.mode(),
                current.acquiredTick(),
                Math.addExact(currentTick, leaseTicks));
        replace(current, renewed);
        return new RenewResult(
                RenewStatus.RENEWED, Optional.of(renewed));
    }

    public ReleaseStatus release(ReservationToken token) {
        requireOwnerThread();
        Objects.requireNonNull(token, "token");
        ReservationToken current =
                leasesById.get(token.reservationId());
        if (current == null) {
            return ReleaseStatus.NOT_FOUND;
        }
        if (!current.equals(token)) {
            return ReleaseStatus.STALE_TOKEN;
        }
        remove(current);
        return ReleaseStatus.RELEASED;
    }

    public int releaseRun(
            UUID botId,
            long botGeneration,
            UUID skillRunId) {
        requireOwnerThread();
        requireIdentity(botId, botGeneration, skillRunId);
        List<ReservationToken> matches =
                leasesById.values().stream()
                        .filter(token -> sameOwner(
                                token,
                                botId,
                                botGeneration,
                                skillRunId))
                        .toList();
        matches.forEach(this::remove);
        return matches.size();
    }

    public int closeGeneration(
            UUID botId, long botGeneration) {
        requireOwnerThread();
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
        List<ReservationToken> matches =
                leasesById.values().stream()
                        .filter(token ->
                                token.botId().equals(botId)
                                        && token.botGeneration()
                                                == botGeneration)
                        .toList();
        matches.forEach(this::remove);
        return matches.size();
    }

    public List<ReservationToken> inspect(
            ReservationKey key, long currentTick) {
        requireOwnerThread();
        Objects.requireNonNull(key, "key");
        expireAt(observeTick(currentTick));
        return List.copyOf(
                leasesByKey.getOrDefault(key, List.of()));
    }

    public int activeLeaseCount(long currentTick) {
        requireOwnerThread();
        expireAt(observeTick(currentTick));
        return leasesById.size();
    }

    private void replace(
            ReservationToken current,
            ReservationToken replacement) {
        leasesById.put(current.reservationId(), replacement);
        List<ReservationToken> keyLeases =
                leasesByKey.get(current.key());
        if (keyLeases == null) {
            throw new IllegalStateException(
                    "reservation key index is missing");
        }
        int index = keyLeases.indexOf(current);
        if (index < 0) {
            throw new IllegalStateException(
                    "reservation key index is inconsistent");
        }
        keyLeases.set(index, replacement);
    }

    private void expireAt(long currentTick) {
        List<ReservationToken> expired =
                leasesById.values().stream()
                        .filter(token ->
                                token.expiredAt(currentTick))
                        .toList();
        expired.forEach(this::remove);
    }

    private void remove(ReservationToken token) {
        if (!leasesById.remove(
                token.reservationId(), token)) {
            return;
        }
        List<ReservationToken> keyLeases =
                leasesByKey.get(token.key());
        if (keyLeases == null
                || !keyLeases.remove(token)) {
            throw new IllegalStateException(
                    "reservation key index is inconsistent");
        }
        if (keyLeases.isEmpty()) {
            leasesByKey.remove(token.key(), keyLeases);
        }
    }

    private long observeTick(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "currentTick must not be negative");
        }
        if (currentTick < lastObservedTick) {
            throw new IllegalArgumentException(
                    "currentTick must not move backwards");
        }
        lastObservedTick = currentTick;
        return currentTick;
    }

    private void validateLeaseTicks(int leaseTicks) {
        if (leaseTicks < 1
                || leaseTicks > maximumLeaseTicks) {
            throw new IllegalArgumentException(
                    "leaseTicks must be between 1 and "
                            + maximumLeaseTicks);
        }
    }

    private UUID nextId() {
        for (int attempt = 0;
                attempt < MAXIMUM_ID_ATTEMPTS;
                attempt++) {
            UUID candidate = idSupplier.get();
            if (candidate != null
                    && !ZERO_UUID.equals(candidate)
                    && !leasesById.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "Resource reservations require the owner server thread");
        }
    }

    private static void requireIdentity(
            UUID botId,
            long botGeneration,
            UUID skillRunId) {
        requireNonZero(botId, "botId");
        requireNonZero(skillRunId, "skillRunId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
    }

    private static void requireNonZero(
            UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(
                    name + " must not be zero");
        }
    }

    private static boolean sameOwner(
            ReservationToken token,
            UUID botId,
            long botGeneration,
            UUID skillRunId) {
        return token.botId().equals(botId)
                && token.botGeneration() == botGeneration
                && token.skillRunId().equals(skillRunId);
    }

    public enum AcquireStatus {
        ACQUIRED,
        ALREADY_HELD,
        CONFLICT,
        CAPACITY_EXHAUSTED,
        ID_UNAVAILABLE
    }

    public record AcquireResult(
            AcquireStatus status,
            Optional<ReservationToken> token) {
        public AcquireResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(token, "token");
            boolean present = token.isPresent();
            if (present != (status == AcquireStatus.ACQUIRED
                    || status == AcquireStatus.ALREADY_HELD)) {
                throw new IllegalArgumentException(
                        "Acquire result token does not match status");
            }
        }

        private static AcquireResult rejected(
                AcquireStatus status) {
            return new AcquireResult(
                    status, Optional.empty());
        }
    }

    public enum RenewStatus {
        RENEWED,
        NOT_FOUND,
        STALE_TOKEN
    }

    public record RenewResult(
            RenewStatus status,
            Optional<ReservationToken> token) {
        public RenewResult {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(token, "token");
            if (token.isPresent()
                    != (status == RenewStatus.RENEWED)) {
                throw new IllegalArgumentException(
                        "Renew result token does not match status");
            }
        }

        private static RenewResult rejected(
                RenewStatus status) {
            return new RenewResult(
                    status, Optional.empty());
        }
    }

    public enum ReleaseStatus {
        RELEASED,
        NOT_FOUND,
        STALE_TOKEN
    }
}
