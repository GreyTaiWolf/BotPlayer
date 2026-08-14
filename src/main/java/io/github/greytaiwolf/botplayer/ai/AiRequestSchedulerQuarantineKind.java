package io.github.greytaiwolf.botplayer.ai;

/** 外部 handoff 未开始、或已占住有界物理 lane 时的隔离来源。 */
public enum AiRequestSchedulerQuarantineKind {
    /** 已交给 broker 的 wrapper 未在独立 start watchdog 前实际开始。 */
    DISPATCH_START,
    PROVIDER_INVOCATION,
    TOKEN_SETUP,
    TERMINAL_CLEANUP,
    COMPLETION_DELIVERY
}
