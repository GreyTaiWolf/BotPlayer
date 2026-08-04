package io.github.greytaiwolf.botplayer.lifecycle.retirement;

/** 一次 generation 退役事务的纯模型状态。 */
public enum GenerationRetirementStatus {
    PENDING,
    COMPLETE,
    UNSAFE
}
