package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 熔断器一次准入尝试的安全结果。
 */
public final class AiCircuitAdmission {
    private final AiCircuitState state;
    private final Optional<AiCircuitPermit> permit;
    private final Optional<Instant> retryAfter;
    private final Optional<AiFailureKind> rejectionKind;

    AiCircuitAdmission(
            AiCircuitState state,
            Optional<AiCircuitPermit> permit,
            Optional<Instant> retryAfter,
            Optional<AiFailureKind> rejectionKind) {
        this.state = Objects.requireNonNull(state, "state");
        this.permit = Objects.requireNonNull(permit, "permit");
        this.retryAfter = Objects.requireNonNull(
                retryAfter, "retryAfter").map(value ->
                        AiChecks.instant(value, "retryAfter value"));
        this.rejectionKind = Objects.requireNonNull(
                rejectionKind, "rejectionKind");
        if (permit.isPresent() == rejectionKind.isPresent()) {
            throw new IllegalArgumentException(
                    "admission must contain exactly one of permit or rejectionKind");
        }
        if (permit.isPresent() && retryAfter.isPresent()) {
            throw new IllegalArgumentException(
                    "accepted admission must not contain retryAfter");
        }
    }

    public boolean accepted() {
        return permit.isPresent();
    }

    public AiCircuitState state() {
        return state;
    }

    public Optional<AiCircuitPermit> permit() {
        return permit;
    }

    public Optional<Instant> retryAfter() {
        return retryAfter;
    }

    public Optional<AiFailureKind> rejectionKind() {
        return rejectionKind;
    }
}
