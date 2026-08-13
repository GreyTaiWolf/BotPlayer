package io.github.greytaiwolf.botplayer.client.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 客户端 Provider 的脱敏异常。
 *
 * <p>异常消息只能包含固定失败码。调用方不得把 HTTP body、Authorization header、API Key 或
 * 原始 Throwable message 拼入本异常、日志、聊天或 Minecraft payload。</p>
 */
public final class DeepSeekProviderException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final DeepSeekFailureCode code;
    private final Instant retryAfter;

    public DeepSeekProviderException(DeepSeekFailureCode code) {
        this(code, Optional.empty());
    }

    public DeepSeekProviderException(
            DeepSeekFailureCode code, Optional<Instant> retryAfter) {
        super("DeepSeek provider failure: "
                + Objects.requireNonNull(code, "code").name());
        this.code = code;
        this.retryAfter = Objects.requireNonNull(retryAfter, "retryAfter")
                .orElse(null);
    }

    public DeepSeekFailureCode code() {
        return code;
    }

    public Optional<Instant> retryAfter() {
        return Optional.ofNullable(retryAfter);
    }
}
