package io.github.greytaiwolf.botplayer.ai;

/** Secret-free, bounded server coordinator diagnostic. */
public record AiPhysicalAttemptBudgetSnapshot(
        int offeredAttempts,
        int committedAttempts,
        int tombstones) {
    public AiPhysicalAttemptBudgetSnapshot {
        if (offeredAttempts < 0 || committedAttempts < 0 || tombstones < 0) {
            throw new IllegalArgumentException("attempt snapshot counts must not be negative");
        }
        try {
            Math.addExact(offeredAttempts, committedAttempts);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("active attempt count overflow", exception);
        }
    }

    public int activeAttempts() {
        return Math.addExact(offeredAttempts, committedAttempts);
    }
}
