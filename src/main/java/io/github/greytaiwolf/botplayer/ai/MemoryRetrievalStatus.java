package io.github.greytaiwolf.botplayer.ai;

/**
 * 组装结果中记忆这一可选输入的处理状态。
 */
public enum MemoryRetrievalStatus {
    NOT_REQUESTED,
    SKIPPED_BY_BUDGET,
    EMPTY,
    RETRIEVED,
    UNAVAILABLE,
    REJECTED
}
