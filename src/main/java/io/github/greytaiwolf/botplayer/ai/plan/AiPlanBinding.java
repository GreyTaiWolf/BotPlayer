package io.github.greytaiwolf.botplayer.ai.plan;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import java.util.Objects;
import java.util.UUID;

/**
 * Server-derived identity and freshness facts attached to one AI planning request.
 *
 * <p>This is intentionally not a network payload and deliberately omits the proposal gate's nonce.
 * The server creates it from an active owner/bot/agent binding and rechecks it before a port accepts or
 * submits a plan. A model may never choose any field in this type.
 */
public record AiPlanBinding(
        UUID requestId,
        UUID botId,
        UUID ownerId,
        UUID agentId,
        long botGeneration,
        long revision,
        UUID observationStreamId,
        long observationSnapshotId,
        long issuedAtTick,
        long expiresAtTick) {
    /** Matches the proposal gate's one-minute bounded client-sponsored session lifetime. */
    public static final int MAX_LIFETIME_TICKS = 20 * 60;

    public AiPlanBinding {
        AiPlanChecks.requireNonZero(requestId, "requestId");
        AiPlanChecks.requireNonZero(botId, "botId");
        AiPlanChecks.requireNonZero(ownerId, "ownerId");
        AiPlanChecks.requireNonZero(agentId, "agentId");
        if (botGeneration < 1L || revision < 1L) {
            throw new IllegalArgumentException("generation and revision must be positive");
        }
        AiPlanChecks.requireNonZero(observationStreamId, "observationStreamId");
        if (observationSnapshotId < 1L || issuedAtTick < 0L
                || expiresAtTick <= issuedAtTick
                || expiresAtTick - issuedAtTick > MAX_LIFETIME_TICKS) {
            throw new IllegalArgumentException("planning binding lifetime or snapshot is invalid");
        }
    }

    /** True only for the exact proposal request issued by this server binding. */
    public boolean matches(ProposedSkillPlan proposal) {
        return requestId.equals(Objects.requireNonNull(proposal, "proposal").requestId());
    }

    /** A response at the expiry tick is stale; the valid interval is half-open. */
    public boolean isExpiredAt(long currentTick) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        return currentTick >= expiresAtTick;
    }

    /** Avoid exposing owner, agent or stream identities in generic diagnostics. */
    @Override
    public String toString() {
        return "AiPlanBinding[generation=" + botGeneration
                + ", revision=" + revision
                + ", snapshotId=" + observationSnapshotId
                + ", issuedAtTick=" + issuedAtTick
                + ", expiresAtTick=" + expiresAtTick + "]";
    }
}
