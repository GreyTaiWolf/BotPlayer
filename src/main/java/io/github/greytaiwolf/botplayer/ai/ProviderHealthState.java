package io.github.greytaiwolf.botplayer.ai;

/**
 * 不含原始响应体或凭据的 Provider 健康状态。
 */
public enum ProviderHealthState {
    UNKNOWN,
    HEALTHY,
    DEGRADED,
    RATE_LIMITED,
    AUTHENTICATION_FAILED,
    QUOTA_EXHAUSTED,
    UNAVAILABLE,
    CIRCUIT_OPEN
}
