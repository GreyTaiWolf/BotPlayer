package io.github.greytaiwolf.botplayer.perception.event;

/**
 * 区分行为尝试与已经提交的世界事实。
 */
public enum SemanticEventOutcome {
    ATTEMPTED,
    COMMITTED,
    CANCELLED,
    FAILED
}
