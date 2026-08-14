package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link AiCircuitBreaker} 的固定上限策略。
 */
public record AiCircuitBreakerPolicy(
        int failureThreshold,
        int halfOpenProbeLimit,
        int maximumInFlightPermits,
        Duration openDuration,
        Duration permitLeaseDuration) {
    public static final int MAX_FAILURE_THRESHOLD = 32;
    public static final int MAX_HALF_OPEN_PROBES = 8;
    public static final int MAX_IN_FLIGHT_PERMITS = 128;
    public static final Duration MAX_DURATION = Duration.ofMinutes(10L);

    public AiCircuitBreakerPolicy {
        if (failureThreshold < 1
                || failureThreshold > MAX_FAILURE_THRESHOLD) {
            throw new IllegalArgumentException(
                    "failureThreshold must be between 1 and "
                            + MAX_FAILURE_THRESHOLD);
        }
        if (halfOpenProbeLimit < 1
                || halfOpenProbeLimit > MAX_HALF_OPEN_PROBES) {
            throw new IllegalArgumentException(
                    "halfOpenProbeLimit must be between 1 and "
                            + MAX_HALF_OPEN_PROBES);
        }
        if (maximumInFlightPermits < halfOpenProbeLimit
                || maximumInFlightPermits > MAX_IN_FLIGHT_PERMITS) {
            throw new IllegalArgumentException(
                    "maximumInFlightPermits must be between halfOpenProbeLimit and "
                            + MAX_IN_FLIGHT_PERMITS);
        }
        openDuration = boundedPositiveDuration(openDuration, "openDuration");
        permitLeaseDuration = boundedPositiveDuration(
                permitLeaseDuration, "permitLeaseDuration");
    }

    public static AiCircuitBreakerPolicy defaults() {
        return new AiCircuitBreakerPolicy(
                3,
                1,
                32,
                Duration.ofSeconds(10L),
                Duration.ofSeconds(30L));
    }

    private static Duration boundedPositiveDuration(
            Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()
                || value.compareTo(MAX_DURATION) > 0) {
            throw new IllegalArgumentException(
                    name + " must be positive and no more than "
                            + MAX_DURATION);
        }
        return value;
    }
}
