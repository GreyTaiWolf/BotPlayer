package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;

/** Secret-free, bounded accounting snapshot for one token-budget ledger. */
public record AiTokenBudgetSnapshot(
        long maximumTokens,
        long reservedTokens,
        long committedTokens,
        int activeReservations,
        boolean closed,
        Instant observedAt) {
    public AiTokenBudgetSnapshot {
        if (maximumTokens < 1L
                || maximumTokens > AiTokenBudgetPolicy.MAXIMUM_TOKENS) {
            throw new IllegalArgumentException("maximumTokens is outside supported bounds");
        }
        if (reservedTokens < 0L || committedTokens < 0L) {
            throw new IllegalArgumentException("token accounting must not be negative");
        }
        long consumed;
        try {
            consumed = Math.addExact(reservedTokens, committedTokens);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "token accounting overflow", exception);
        }
        if (consumed > maximumTokens) {
            throw new IllegalArgumentException("token accounting exceeds maximumTokens");
        }
        if (activeReservations < 0
                || activeReservations
                > AiTokenBudgetPolicy.MAXIMUM_ACTIVE_RESERVATIONS) {
            throw new IllegalArgumentException(
                    "activeReservations is outside supported bounds");
        }
        observedAt = AiChecks.instant(observedAt, "observedAt");
    }

    public long consumedTokens() {
        return reservedTokens + committedTokens;
    }

    public long availableTokens() {
        return maximumTokens - consumedTokens();
    }
}
