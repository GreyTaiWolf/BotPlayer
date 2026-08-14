package io.github.greytaiwolf.botplayer.ai;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Provider 请求的有限指数退避策略。
 *
 * <p>同一 requestId 最多尝试 {@link #maximumAttempts()} 次；退避在硬上限内附加正向抖动，
 * 避免 429/5xx 并发失败后齐发。测试可注入固定 {@link AiRetryJitter}，真正的调度仍由
 * {@link RetryingAiProvider} 注入的 executor 完成。
 */
public record AiRetryPolicy(
        int maximumAttempts,
        int maximumInFlightRequests,
        Duration initialBackoff,
        Duration maximumBackoff,
        Duration maximumProviderRetryAfter,
        AiRetryJitter jitter) {
    public static final int MAX_ATTEMPTS = 8;
    public static final int MAX_IN_FLIGHT_REQUESTS = 128;
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(5L);
    public static final int MAX_JITTER_PERCENT = 25;

    public AiRetryPolicy {
        if (maximumAttempts < 1 || maximumAttempts > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "maximumAttempts must be between 1 and "
                            + MAX_ATTEMPTS);
        }
        if (maximumInFlightRequests < 1
                || maximumInFlightRequests > MAX_IN_FLIGHT_REQUESTS) {
            throw new IllegalArgumentException(
                    "maximumInFlightRequests must be between 1 and "
                            + MAX_IN_FLIGHT_REQUESTS);
        }
        initialBackoff = boundedNonNegative(
                initialBackoff, "initialBackoff");
        maximumBackoff = boundedNonNegative(
                maximumBackoff, "maximumBackoff");
        maximumProviderRetryAfter = boundedNonNegative(
                maximumProviderRetryAfter, "maximumProviderRetryAfter");
        jitter = AiRetryJitter.checked(jitter);
        if (initialBackoff.compareTo(maximumBackoff) > 0) {
            throw new IllegalArgumentException(
                    "initialBackoff must not exceed maximumBackoff");
        }
    }

    public AiRetryPolicy(
            int maximumAttempts,
            int maximumInFlightRequests,
            Duration initialBackoff,
            Duration maximumBackoff,
            Duration maximumProviderRetryAfter) {
        this(
                maximumAttempts,
                maximumInFlightRequests,
                initialBackoff,
                maximumBackoff,
                maximumProviderRetryAfter,
                AiRetryJitter.defaults());
    }

    public static AiRetryPolicy defaults() {
        return new AiRetryPolicy(
                3,
                32,
                Duration.ofMillis(250L),
                Duration.ofSeconds(5L),
                Duration.ofSeconds(30L),
                AiRetryJitter.defaults());
    }

    /**
     * 对刚完成的失败计算下一次尝试；超过 deadline 或预算时返回不重试决定。
     */
    public AiRetryDecision decide(
            AiProviderException exception,
            int completedAttempts,
            Instant now,
            Instant deadline) {
        Objects.requireNonNull(exception, "exception");
        Instant observedAt = AiChecks.instant(now, "now");
        Instant checkedDeadline = AiChecks.instant(deadline, "deadline");
        if (completedAttempts < 1 || completedAttempts > maximumAttempts
                || !exception.retryable()
                || completedAttempts >= maximumAttempts
                || !observedAt.isBefore(checkedDeadline)) {
            return AiRetryDecision.stop(exception.failureKind(), completedAttempts);
        }
        Duration delay = exponentialDelay(completedAttempts);
        Optional<Instant> providerRetryAfter = exception.retryAfter();
        if (providerRetryAfter.isPresent()) {
            Duration requested = durationFrom(
                    observedAt, providerRetryAfter.orElseThrow());
            if (requested.compareTo(maximumProviderRetryAfter) > 0) {
                requested = maximumProviderRetryAfter;
            }
            if (requested.compareTo(delay) > 0) {
                delay = requested;
            }
        }
        delay = applyJitter(delay);
        Instant retryAt = plus(observedAt, delay);
        if (!retryAt.isBefore(checkedDeadline)) {
            return AiRetryDecision.stop(exception.failureKind(), completedAttempts);
        }
        return AiRetryDecision.retry(
                exception.failureKind(), completedAttempts + 1, retryAt);
    }

    private Duration exponentialDelay(int completedAttempts) {
        long baseMillis = initialBackoff.toMillis();
        long maximumMillis = maximumBackoff.toMillis();
        long delayMillis = baseMillis;
        for (int index = 1; index < completedAttempts
                && delayMillis < maximumMillis; index++) {
            if (delayMillis > maximumMillis / 2L) {
                delayMillis = maximumMillis;
            } else {
                delayMillis *= 2L;
            }
        }
        return Duration.ofMillis(Math.min(delayMillis, maximumMillis));
    }

    private Duration applyJitter(Duration delay) {
        long delayMillis = delay.toMillis();
        if (delayMillis <= 0L) {
            return Duration.ZERO;
        }
        long maximumDelayMillis = Math.max(
                maximumBackoff.toMillis(),
                maximumProviderRetryAfter.toMillis());
        long capacity = Math.max(0L, maximumDelayMillis - delayMillis);
        long percentageOffset = (delayMillis * MAX_JITTER_PERCENT
                + 99L) / 100L;
        long maximumOffset = Math.min(capacity, percentageOffset);
        if (maximumOffset == 0L) {
            return Duration.ofMillis(delayMillis);
        }
        long candidate;
        try {
            candidate = jitter.nextOffsetMillis(maximumOffset);
        } catch (RuntimeException exception) {
            // 测试注入器异常时保守地不用抖动，不能把异常正文带到调用边界。
            return Duration.ofMillis(delayMillis);
        }
        long normalized = candidate >= 0L && candidate <= maximumOffset
                ? candidate
                : Math.floorMod(candidate, maximumOffset + 1L);
        return Duration.ofMillis(delayMillis + normalized);
    }

    private static Duration durationFrom(Instant start, Instant target) {
        if (!target.isAfter(start)) {
            return Duration.ZERO;
        }
        try {
            return Duration.between(start, target);
        } catch (DateTimeException exception) {
            return MAX_BACKOFF;
        }
    }

    private static Instant plus(Instant instant, Duration duration) {
        try {
            return instant.plus(duration);
        } catch (DateTimeException exception) {
            throw new IllegalArgumentException(
                    "retry deadline exceeds Instant range");
        }
    }

    private static Duration boundedNonNegative(
            Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isNegative() || value.compareTo(MAX_BACKOFF) > 0) {
            throw new IllegalArgumentException(
                    name + " must be between zero and " + MAX_BACKOFF);
        }
        return value;
    }
}
