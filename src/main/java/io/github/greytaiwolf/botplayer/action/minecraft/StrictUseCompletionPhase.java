package io.github.greytaiwolf.botplayer.action.minecraft;

/**
 * One-way native completion boundary for a strict natural item use.
 *
 * <p>{@link #ENTERED} is written immediately before vanilla invokes
 * {@code LivingEntity.completeUsingItem()}. It does not assert that vanilla
 * successfully produced a particular inventory result; the normal action
 * verifier remains the only proof of that. It only establishes that a later
 * cancellation is no longer allowed to overwrite that physical outcome.
 */
enum StrictUseCompletionPhase {
    OPEN,
    ENTERED;

    boolean hasEnteredNativeCompletion() {
        return this == ENTERED;
    }
}
