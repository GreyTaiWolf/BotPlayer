package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReceipt;
import java.util.Objects;

/** Exact containment result for one previously enqueued Technique child Action. */
public record TechniqueActionCancellation(
        TechniqueActionPermit permit,
        TechniqueActionCancellationReason reason,
        ActionCancellationReceipt receipt) {
    public TechniqueActionCancellation {
        permit = Objects.requireNonNull(permit, "permit");
        reason = Objects.requireNonNull(reason, "reason");
        receipt = Objects.requireNonNull(receipt, "receipt");
        if (!receipt.matches(permit.botId(), permit.botGeneration(),
                permit.actionId())) {
            throw new IllegalArgumentException(
                    "Technique Action cancellation receipt did not match its handle");
        }
    }

    public boolean safelyRetracted() {
        return receipt.safelyRetracted();
    }
}
