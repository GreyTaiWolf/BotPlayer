package io.github.greytaiwolf.botplayer.lifecycle.retirement;

/**
 * generation 安全退役后唯一允许继续执行的生命周期分支。
 */
public enum GenerationRetirementContinuation {
    DISCONNECT_PRE_SAVE,
    DEATH_RESPAWN,
    DIMENSION_ROTATE,
    REPLACEMENT_HANDOFF,
    SERVER_STOP
}
