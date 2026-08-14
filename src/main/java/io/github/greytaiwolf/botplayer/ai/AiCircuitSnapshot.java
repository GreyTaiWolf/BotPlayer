package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 熔断器状态的有界、无敏感诊断快照。
 */
public record AiCircuitSnapshot(
        AiCircuitState state,
        int consecutiveTransientFailures,
        int inFlightPermits,
        Instant observedAt,
        Optional<Instant> retryAfter) {
    public AiCircuitSnapshot {
        state = Objects.requireNonNull(state, "state");
        if (consecutiveTransientFailures < 0
                || consecutiveTransientFailures
                > AiCircuitBreakerPolicy.MAX_FAILURE_THRESHOLD) {
            throw new IllegalArgumentException(
                    "consecutiveTransientFailures is outside supported bounds");
        }
        if (inFlightPermits < 0
                || inFlightPermits
                > AiCircuitBreakerPolicy.MAX_IN_FLIGHT_PERMITS) {
            throw new IllegalArgumentException(
                    "inFlightPermits is outside supported bounds");
        }
        Instant checkedObservedAt = AiChecks.instant(observedAt, "observedAt");
        observedAt = checkedObservedAt;
        retryAfter = Objects.requireNonNull(
                retryAfter, "retryAfter").map(value ->
                        AiChecks.instant(value, "retryAfter value"));
        if (retryAfter.isPresent()
                && retryAfter.orElseThrow().isBefore(checkedObservedAt)) {
            throw new IllegalArgumentException(
                    "retryAfter must not be before observedAt");
        }
        if (state == AiCircuitState.OPEN && retryAfter.isEmpty()) {
            throw new IllegalArgumentException(
                    "OPEN circuit snapshots require retryAfter");
        }
        if (state != AiCircuitState.OPEN && retryAfter.isPresent()) {
            throw new IllegalArgumentException(
                    "only OPEN circuit snapshots may contain retryAfter");
        }
    }
}
