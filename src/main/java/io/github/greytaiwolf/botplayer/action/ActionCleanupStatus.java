package io.github.greytaiwolf.botplayer.action;

/**
 * 动作物理清理的一步结果。
 */
public enum ActionCleanupStatus {
    /** 已证明动作持有的物理状态处于安全端点。 */
    COMPLETE,
    /** 已知可重放的中间态；必须在指定的未来 Tick 继续。 */
    PENDING,
    /** 无法证明安全，调用者必须隔离精确 generation。 */
    UNSAFE
}
