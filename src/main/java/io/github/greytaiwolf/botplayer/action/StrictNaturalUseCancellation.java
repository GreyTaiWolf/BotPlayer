package io.github.greytaiwolf.botplayer.action;

/**
 * Exact cancellation admission for a strict natural item-use action.
 *
 * <p>This is deliberately separate from {@link ActionMailbox.CancellationStatus}:
 * {@link #COMPLETION_ENTERED} and {@link #ALREADY_TERMINAL} mean that no
 * mailbox cancellation was attempted. The physical native-use boundary has
 * already won, so a later cancellation must wait for the exact action outcome
 * instead of being made to look like a successful retraction.
 */
public enum StrictNaturalUseCancellation {
    /** The native fence was armed and the exact cancellation entered the mailbox. */
    FENCED,
    /** Native {@code completeUsingItem()} has been entered for this exact action. */
    COMPLETION_ENTERED,
    /** The exact action has already reached a retained terminal outcome. */
    ALREADY_TERMINAL,
    /** Cancellation ingress was rejected and the generation was contained fail-closed. */
    REJECTED_CONTAINED;

    /** Whether the owning SkillRuntime must await the existing exact action outcome. */
    public boolean awaitsExactActionOutcome() {
        return this == COMPLETION_ENTERED || this == ALREADY_TERMINAL;
    }
}
