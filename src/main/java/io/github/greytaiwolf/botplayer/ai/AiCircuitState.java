package io.github.greytaiwolf.botplayer.ai;

/**
 * 有界 Provider 熔断器状态。
 */
public enum AiCircuitState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
