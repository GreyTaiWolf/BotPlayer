package io.github.greytaiwolf.botplayer.ai;

/** Bounded aggregate from disconnect/lifecycle closure without exposing attempt identities. */
public record AiPhysicalAttemptCloseSummary(
        int closedUnstartedAttempts,
        int closedCommittedAttempts) {
    public AiPhysicalAttemptCloseSummary {
        if (closedUnstartedAttempts < 0 || closedCommittedAttempts < 0) {
            throw new IllegalArgumentException("close summary counts must not be negative");
        }
        try {
            Math.addExact(closedUnstartedAttempts, closedCommittedAttempts);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("close summary count overflow", exception);
        }
    }

    public int closedAttempts() {
        return Math.addExact(closedUnstartedAttempts, closedCommittedAttempts);
    }
}
