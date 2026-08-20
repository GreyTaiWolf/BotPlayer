package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.skill.reservation.ReservationKey;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationMode;
import io.github.greytaiwolf.botplayer.skill.reservation.ReservationToken;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Opaque, process-local proof that one exact construction-site binding currently owns its
 * conservative spatial reservation tiles.
 *
 * <p>This value is issued only by {@link ConstructionSiteLeaseService}. It intentionally exposes
 * audit identity and immutable site data but not the underlying {@link ReservationToken}s, so it
 * cannot become a generic resource-reservation or world-mutation capability. A caller must ask its
 * issuing service whether the lease is still current before treating it as usable.
 */
public final class ConstructionSiteLease {
    private final UUID botId;
    private final long botGeneration;
    private final UUID skillRunId;
    private final ConstructionSiteBinding binding;
    private final List<ReservationToken> tokens;
    private final long acquiredTick;
    private final long expiresTick;

    ConstructionSiteLease(UUID botId, long botGeneration, UUID skillRunId,
            ConstructionSiteBinding binding, List<ReservationToken> tokens) {
        this.botId = requireNonZero(botId, "botId");
        if (botGeneration < 1L) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        this.botGeneration = botGeneration;
        this.skillRunId = requireNonZero(skillRunId, "skillRunId");
        this.binding = Objects.requireNonNull(binding, "binding");
        this.tokens = checkedTokens(tokens);
        ReservationToken first = this.tokens.get(0);
        this.acquiredTick = first.acquiredTick();
        this.expiresTick = first.expiresTick();
    }

    public UUID botId() {
        return botId;
    }

    public long botGeneration() {
        return botGeneration;
    }

    public UUID skillRunId() {
        return skillRunId;
    }

    /** Returns the complete immutable binding that was exact when this lease was issued. */
    public ConstructionSiteBinding binding() {
        return binding;
    }

    public long acquiredTick() {
        return acquiredTick;
    }

    public long expiresTick() {
        return expiresTick;
    }

    /** Returns the bounded number of conservative 32-block spatial tiles held by this lease. */
    public int tileCount() {
        return tokens.size();
    }

    List<ReservationToken> tokens() {
        return tokens;
    }

    boolean hasExactBinding(ConstructionSiteBinding candidate) {
        return binding.equals(Objects.requireNonNull(candidate, "candidate"));
    }

    private List<ReservationToken> checkedTokens(List<ReservationToken> supplied) {
        Objects.requireNonNull(supplied, "tokens");
        if (supplied.isEmpty()
                || supplied.size() > ConstructionSiteLeaseService.MAX_TILES_PER_BINDING) {
            throw new IllegalArgumentException("construction site lease must hold 1-"
                    + ConstructionSiteLeaseService.MAX_TILES_PER_BINDING + " tiles");
        }
        List<ReservationToken> canonical = List.copyOf(supplied);
        Set<ReservationKey> keys = new HashSet<>();
        long expectedAcquiredTick = -1L;
        long expectedExpiresTick = -1L;
        for (ReservationToken token : canonical) {
            ReservationToken checkedToken = Objects.requireNonNull(token, "reservation token");
            if (!botId.equals(checkedToken.botId())
                    || botGeneration != checkedToken.botGeneration()
                    || !skillRunId.equals(checkedToken.skillRunId())
                    || checkedToken.mode() != ReservationMode.EXCLUSIVE
                    || checkedToken.key().kind() != ReservationKey.Kind.WORK_AREA) {
                throw new IllegalArgumentException(
                        "construction site lease token did not match its exact owner");
            }
            if (!keys.add(checkedToken.key())) {
                throw new IllegalArgumentException(
                        "construction site lease tokens must use distinct spatial keys");
            }
            if (expectedAcquiredTick < 0L) {
                expectedAcquiredTick = checkedToken.acquiredTick();
                expectedExpiresTick = checkedToken.expiresTick();
            } else if (expectedAcquiredTick != checkedToken.acquiredTick()
                    || expectedExpiresTick != checkedToken.expiresTick()) {
                throw new IllegalArgumentException(
                        "construction site lease tokens must share one lease window");
            }
        }
        if (!keys.equals(new HashSet<>(ConstructionSiteLeaseService.tileKeysFor(binding)))) {
            throw new IllegalArgumentException(
                    "construction site lease tokens did not match the exact site binding");
        }
        return canonical;
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (required.getMostSignificantBits() == 0L
                && required.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
        return required;
    }
}
