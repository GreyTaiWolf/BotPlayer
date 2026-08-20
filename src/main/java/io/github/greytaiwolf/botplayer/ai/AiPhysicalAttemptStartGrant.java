package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/**
 * Server-committed permission for the client to perform one locally fenced Provider handoff.
 *
 * <p>A grant exists only after the server ledger returned {@link AiTokenBudgetOperationStatus#SETTLED}.
 * Across the network, that settlement means the server committed budget for a possible physical
 * start; it is deliberately <strong>not</strong> evidence that HTTP actually started, reached a
 * provider, or completed. A lost grant, terminal report, or client disconnect never refunds that
 * committed accounting.
 */
public record AiPhysicalAttemptStartGrant(AiPhysicalAttemptIdentity identity) {
    public AiPhysicalAttemptStartGrant {
        identity = Objects.requireNonNull(identity, "identity");
    }

    @Override
    public String toString() {
        return "AiPhysicalAttemptStartGrant[identityBound=true]";
    }
}
