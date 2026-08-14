package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * {@link ChaosAiProvider} 在指定调用序号注入的确定性失败。
 *
 * <p>它模拟的是 Provider 边界的安全失败结果，不模拟网络、不创建线程，也不保留原始异常。
 */
public record ChaosAiFault(
        int invocation,
        AiFailureKind failureKind,
        Optional<Duration> retryAfterDelay,
        ChaosAiFaultMode mode,
        Optional<Duration> delay) {
    public static final int MAX_INVOCATION = 4_096;
    public static final Duration MAX_RETRY_AFTER_DELAY =
            Duration.ofMinutes(5L);

    public ChaosAiFault(
            int invocation,
            AiFailureKind failureKind,
            Optional<Duration> retryAfterDelay) {
        this(
                invocation,
                failureKind,
                retryAfterDelay,
                ChaosAiFaultMode.FAILURE,
                Optional.empty());
    }

    public ChaosAiFault {
        if (invocation < 1 || invocation > MAX_INVOCATION) {
            throw new IllegalArgumentException(
                    "invocation must be between 1 and " + MAX_INVOCATION);
        }
        Objects.requireNonNull(failureKind, "failureKind");
        retryAfterDelay = Objects.requireNonNull(
                retryAfterDelay, "retryAfterDelay");
        retryAfterDelay.ifPresent(retryDelay -> {
            if (retryDelay.isNegative()
                    || retryDelay.compareTo(MAX_RETRY_AFTER_DELAY) > 0) {
                throw new IllegalArgumentException(
                        "retryAfterDelay must be between zero and "
                                + MAX_RETRY_AFTER_DELAY);
            }
        });
        if (retryAfterDelay.isPresent()
                && failureKind != AiFailureKind.RATE_LIMITED) {
            throw new IllegalArgumentException(
                    "retryAfterDelay is only valid for RATE_LIMITED");
        }
        mode = Objects.requireNonNull(mode, "mode");
        delay = Objects.requireNonNull(delay, "delay");
        delay.ifPresent(value -> {
            if (value.isNegative()
                    || value.compareTo(MAX_RETRY_AFTER_DELAY) > 0) {
                throw new IllegalArgumentException(
                        "delay must be between zero and "
                                + MAX_RETRY_AFTER_DELAY);
            }
        });
        if (mode.delayed() != delay.isPresent()) {
            throw new IllegalArgumentException(
                    "delay presence must exactly match delayed mode");
        }
        if (mode.malformedResponse()
                && failureKind != AiFailureKind.MALFORMED_RESPONSE) {
            throw new IllegalArgumentException(
                    "malformed-response modes require MALFORMED_RESPONSE");
        }
    }
}
