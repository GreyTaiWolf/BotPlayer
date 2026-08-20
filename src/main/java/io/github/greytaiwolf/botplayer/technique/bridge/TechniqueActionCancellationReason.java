package io.github.greytaiwolf.botplayer.technique.bridge;

import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;

/** Exact parent reason retained when a Technique retracts a child Action. */
public enum TechniqueActionCancellationReason {
    REQUESTED(ActionCancellationReason.REQUESTED),
    SAFETY_PREEMPTION(ActionCancellationReason.REQUESTED),
    GENERATION_CHANGED(ActionCancellationReason.LIFECYCLE),
    RUNTIME_SHUTDOWN(ActionCancellationReason.RUNTIME_SHUTDOWN);

    private final ActionCancellationReason actionReason;

    TechniqueActionCancellationReason(ActionCancellationReason actionReason) {
        this.actionReason = actionReason;
    }

    /** P2 has no L0 enum; the richer Technique reason remains in this DTO. */
    public ActionCancellationReason actionReason() {
        return actionReason;
    }
}
