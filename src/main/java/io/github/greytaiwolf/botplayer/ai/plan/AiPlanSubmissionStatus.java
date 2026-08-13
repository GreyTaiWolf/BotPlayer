package io.github.greytaiwolf.botplayer.ai.plan;

/** Stable result codes returned by the P5-owned submission adapter. */
public enum AiPlanSubmissionStatus {
    SUBMITTED,
    BINDING_STALE,
    TOKEN_NOT_ISSUED_BY_PORT,
    CAPACITY_EXHAUSTED,
    EXECUTION_REJECTED,
    PORT_NOT_READY,
    INTERNAL_FAILURE
}
