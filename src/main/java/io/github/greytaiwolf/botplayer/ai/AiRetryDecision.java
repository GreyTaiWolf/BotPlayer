package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 单次失败后的有限重试决定。
 */
public record AiRetryDecision(
        boolean retry,
        AiFailureKind failureKind,
        int nextAttempt,
        Optional<Instant> retryAt) {
    public AiRetryDecision {
        Objects.requireNonNull(failureKind, "failureKind");
        if (nextAttempt < 1 || nextAttempt > AiRetryPolicy.MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "nextAttempt is outside supported bounds");
        }
        retryAt = Objects.requireNonNull(retryAt, "retryAt").map(value ->
                AiChecks.instant(value, "retryAt value"));
        if (retry != retryAt.isPresent()) {
            throw new IllegalArgumentException(
                    "retry must exactly match retryAt presence");
        }
    }

    public static AiRetryDecision retry(
            AiFailureKind failureKind, int nextAttempt, Instant retryAt) {
        return new AiRetryDecision(
                true,
                failureKind,
                nextAttempt,
                Optional.of(AiChecks.instant(retryAt, "retryAt")));
    }

    public static AiRetryDecision stop(
            AiFailureKind failureKind, int completedAttempts) {
        int boundedAttempt = Math.max(1, Math.min(
                completedAttempts, AiRetryPolicy.MAX_ATTEMPTS));
        return new AiRetryDecision(
                false,
                failureKind,
                boundedAttempt,
                Optional.empty());
    }
}
