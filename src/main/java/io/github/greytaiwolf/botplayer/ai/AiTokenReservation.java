package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Opaque-by-identity capability for one not-yet-started physical Provider attempt.
 *
 * <p>The ledger accepts only the exact instance it returned. Its random ID avoids accidental
 * collisions, while instance identity prevents a reconstructed value from releasing or settling a
 * live reservation.</p>
 */
public record AiTokenReservation(
        UUID reservationId,
        AiTokenBudgetRequestBinding binding,
        long estimatedInputTokens,
        long reservedOutputTokens,
        long reservedTotalTokens,
        Instant reservedAt,
        Instant expiresAt) {
    public AiTokenReservation {
        AiChecks.requireNonZero(reservationId, "reservationId");
        binding = Objects.requireNonNull(binding, "binding");
        long expectedTotal;
        try {
            expectedTotal = Math.addExact(estimatedInputTokens, reservedOutputTokens);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "token reservation accounting overflow", exception);
        }
        if (estimatedInputTokens < 0L || reservedOutputTokens < 0L
                || reservedTotalTokens <= 0L || reservedTotalTokens != expectedTotal) {
            throw new IllegalArgumentException("token reservation accounting is invalid");
        }
        reservedAt = AiChecks.instant(reservedAt, "reservedAt");
        expiresAt = AiChecks.instant(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(reservedAt)) {
            throw new IllegalArgumentException("expiresAt must be after reservedAt");
        }
    }

    /** Exact identifiers and scope are deliberately omitted from ordinary diagnostics. */
    @Override
    public String toString() {
        return "AiTokenReservation[reservedTotalTokens=" + reservedTotalTokens
                + ", reservedAt=" + reservedAt
                + ", expiresAt=" + expiresAt + "]";
    }
}
