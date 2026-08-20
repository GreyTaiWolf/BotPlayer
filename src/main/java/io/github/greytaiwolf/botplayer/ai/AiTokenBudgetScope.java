package io.github.greytaiwolf.botplayer.ai;

import java.util.UUID;

/**
 * One runtime-local token-budget ownership boundary.
 *
 * <p>This binds an existing server-side owner, bot and independent agent. It is neither a
 * credential binding nor a client-sponsored session receipt, and it must not be persisted or used
 * as an authorization decision.</p>
 */
public record AiTokenBudgetScope(UUID ownerId, UUID botId, UUID agentId) {
    public AiTokenBudgetScope {
        AiChecks.requireNonZero(ownerId, "ownerId");
        AiChecks.requireNonZero(botId, "botId");
        AiChecks.requireNonZero(agentId, "agentId");
    }

    /** Ownership identifiers are deliberately omitted from ordinary diagnostics. */
    @Override
    public String toString() {
        return "AiTokenBudgetScope[bound=true]";
    }
}
