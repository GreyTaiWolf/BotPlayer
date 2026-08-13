package io.github.greytaiwolf.botplayer.ai;

/**
 * Provider 边界可公开、可重试判定的失败类别。
 *
 * <p>枚举不携带 HTTP 原文、异常正文或凭据；调用方只能据此决定有限重试和健康状态。
 */
public enum AiFailureKind {
    CANCELLED(false, ProviderHealthState.DEGRADED),
    INVALID_REQUEST(false, ProviderHealthState.DEGRADED),
    AUTHENTICATION(false, ProviderHealthState.AUTHENTICATION_FAILED),
    RATE_LIMITED(true, ProviderHealthState.RATE_LIMITED),
    QUOTA_EXHAUSTED(false, ProviderHealthState.QUOTA_EXHAUSTED),
    TIMEOUT(true, ProviderHealthState.UNAVAILABLE),
    UNAVAILABLE(true, ProviderHealthState.UNAVAILABLE),
    MALFORMED_RESPONSE(false, ProviderHealthState.DEGRADED),
    OVERLOADED(true, ProviderHealthState.DEGRADED),
    CIRCUIT_OPEN(false, ProviderHealthState.CIRCUIT_OPEN),
    UNKNOWN(false, ProviderHealthState.DEGRADED);

    private final boolean retryable;
    private final ProviderHealthState healthState;

    AiFailureKind(boolean retryable, ProviderHealthState healthState) {
        this.retryable = retryable;
        this.healthState = healthState;
    }

    /**
     * 是否允许由有限重试策略再次发起同一 requestId 的请求。
     */
    public boolean retryable() {
        return retryable;
    }

    /**
     * 将失败归一化为不含原始细节的 Provider 健康状态。
     */
    public ProviderHealthState healthState() {
        return healthState;
    }

    /**
     * 此失败类别对应的固定、非敏感失败码。
     */
    public AiReasonCode defaultReasonCode() {
        return switch (this) {
            case CANCELLED -> AiReasonCode.CANCELLED;
            case INVALID_REQUEST -> AiReasonCode.INVALID_REQUEST;
            case AUTHENTICATION -> AiReasonCode.AUTHENTICATION_FAILED;
            case RATE_LIMITED -> AiReasonCode.RATE_LIMITED;
            case QUOTA_EXHAUSTED -> AiReasonCode.QUOTA_EXHAUSTED;
            case TIMEOUT -> AiReasonCode.TIMEOUT;
            case UNAVAILABLE -> AiReasonCode.UNAVAILABLE;
            case MALFORMED_RESPONSE -> AiReasonCode.MALFORMED_RESPONSE;
            case OVERLOADED -> AiReasonCode.OVERLOADED;
            case CIRCUIT_OPEN -> AiReasonCode.CIRCUIT_OPEN;
            case UNKNOWN -> AiReasonCode.UNKNOWN;
        };
    }
}
