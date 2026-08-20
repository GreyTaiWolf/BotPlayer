package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.util.Objects;

/** Fixed, bounded accounting policy for exactly one {@link AiTokenBudgetScope}. */
public record AiTokenBudgetPolicy(
        long maximumTokens,
        int maximumActiveReservations,
        Duration maximumReservationAge) {
    public static final long MAXIMUM_TOKENS = 1_000_000_000L;
    public static final int MAXIMUM_ACTIVE_RESERVATIONS = 128;
    public static final Duration MAXIMUM_RESERVATION_AGE = Duration.ofMinutes(10L);

    public AiTokenBudgetPolicy {
        if (maximumTokens < 1L || maximumTokens > MAXIMUM_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumTokens must be between 1 and " + MAXIMUM_TOKENS);
        }
        if (maximumActiveReservations < 1
                || maximumActiveReservations > MAXIMUM_ACTIVE_RESERVATIONS) {
            throw new IllegalArgumentException(
                    "maximumActiveReservations must be between 1 and "
                            + MAXIMUM_ACTIVE_RESERVATIONS);
        }
        maximumReservationAge = Objects.requireNonNull(
                maximumReservationAge, "maximumReservationAge");
        if (maximumReservationAge.isZero() || maximumReservationAge.isNegative()
                || maximumReservationAge.compareTo(MAXIMUM_RESERVATION_AGE) > 0) {
            throw new IllegalArgumentException(
                    "maximumReservationAge must be positive and no more than "
                            + MAXIMUM_RESERVATION_AGE);
        }
    }
}
