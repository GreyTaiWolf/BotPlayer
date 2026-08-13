package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;

/** Bounded result of checking an {@link AiRequest} against configuration and a capability snapshot. */
public record AiModelAdmission(
        AiModelAdmissionStatus status,
        long estimatedInputTokens,
        long reservedOutputTokens,
        long reservedTotalTokens) {
    public AiModelAdmission {
        status = Objects.requireNonNull(status, "status");
        long expectedTotal;
        try {
            expectedTotal = Math.addExact(
                    estimatedInputTokens, reservedOutputTokens);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "model admission token accounting overflow", exception);
        }
        if (estimatedInputTokens < 0L || reservedOutputTokens < 0L
                || reservedTotalTokens < 0L
                || reservedTotalTokens != expectedTotal) {
            throw new IllegalArgumentException("model admission token accounting is invalid");
        }
    }

    public boolean accepted() {
        return status == AiModelAdmissionStatus.ACCEPTED;
    }

    @Override
    public String toString() {
        return "AiModelAdmission[status=" + status
                + ", estimatedInputTokens=" + estimatedInputTokens
                + ", reservedOutputTokens=" + reservedOutputTokens
                + ", reservedTotalTokens=" + reservedTotalTokens + "]";
    }
}
