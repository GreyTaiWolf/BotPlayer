package io.github.greytaiwolf.botplayer.ai;

/**
 * 一个 AI request 的脱敏、有限生命周期状态。
 */
public enum AiRequestHealthState {
    QUEUED,
    IN_FLIGHT,
    BACKING_OFF,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED
}
