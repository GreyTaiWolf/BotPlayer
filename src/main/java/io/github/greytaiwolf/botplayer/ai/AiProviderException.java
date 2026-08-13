package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Provider 边界的脱敏失败。
 *
 * <p>本异常故意不保留 cause、原始响应或异常消息，避免调用方把 Authorization、提示词或
 * provider 返回体写入普通日志。需要保留原始诊断时必须在未来的客户端专用、脱敏审计边界处理。
 */
public final class AiProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final AiFailureKind failureKind;
    private final Optional<Instant> retryAfter;
    private final AiReasonCode reasonCode;

    public AiProviderException(
            AiFailureKind failureKind,
            Optional<Instant> retryAfter,
            AiReasonCode reasonCode) {
        super(message(failureKind, reasonCode));
        this.failureKind = Objects.requireNonNull(
                failureKind, "failureKind");
        this.retryAfter = Objects.requireNonNull(
                retryAfter, "retryAfter").map(value ->
                        AiChecks.instant(value, "retryAfter value"));
        this.reasonCode = Objects.requireNonNull(reasonCode, "reasonCode");
    }

    public static AiProviderException of(AiFailureKind failureKind) {
        Objects.requireNonNull(failureKind, "failureKind");
        return new AiProviderException(
                failureKind,
                Optional.empty(),
                failureKind.defaultReasonCode());
    }

    public static AiProviderException of(
            AiFailureKind failureKind, AiReasonCode reasonCode) {
        return new AiProviderException(
                Objects.requireNonNull(failureKind, "failureKind"),
                Optional.empty(),
                Objects.requireNonNull(reasonCode, "reasonCode"));
    }

    public static AiProviderException rateLimited(Instant retryAfter) {
        return new AiProviderException(
                AiFailureKind.RATE_LIMITED,
                Optional.of(AiChecks.instant(retryAfter, "retryAfter")),
                AiReasonCode.RATE_LIMITED);
    }

    /**
     * 将未知第三方异常收敛为安全类别，不传递异常正文或 cause。
     */
    public static AiProviderException fromThrowable(Throwable throwable) {
        Throwable current = Objects.requireNonNull(throwable, "throwable");
        for (int depth = 0; depth < 4; depth++) {
            if ((current instanceof CompletionException
                    || current instanceof ExecutionException)
                    && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            break;
        }
        if (current instanceof AiProviderException providerException) {
            return providerException;
        }
        if (current instanceof CancellationException) {
            return AiProviderException.of(AiFailureKind.CANCELLED);
        }
        if (current instanceof TimeoutException) {
            return AiProviderException.of(AiFailureKind.TIMEOUT);
        }
        return AiProviderException.of(AiFailureKind.UNKNOWN);
    }

    public AiFailureKind failureKind() {
        return failureKind;
    }

    public Optional<Instant> retryAfter() {
        return retryAfter;
    }

    public AiReasonCode reasonCode() {
        return reasonCode;
    }

    public boolean retryable() {
        return failureKind.retryable();
    }

    private static String message(
            AiFailureKind failureKind, AiReasonCode reasonCode) {
        Objects.requireNonNull(failureKind, "failureKind");
        AiReasonCode checkedReason = Objects.requireNonNull(
                reasonCode, "reasonCode");
        return "AI provider failure: " + checkedReason.wireCode();
    }
}
