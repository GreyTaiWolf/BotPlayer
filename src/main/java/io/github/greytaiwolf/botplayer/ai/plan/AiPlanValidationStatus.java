package io.github.greytaiwolf.botplayer.ai.plan;

/** Stable, non-secret result codes for the P5 plan-validation boundary. */
public enum AiPlanValidationStatus {
    ACCEPTED,
    REQUEST_MISMATCH,
    REQUEST_ALREADY_CONSUMED,
    BINDING_STALE,
    POLICY_REJECTED,
    TOOL_MAPPING_REJECTED,
    PLAN_INVALID,
    PORT_NOT_READY,
    INTERNAL_FAILURE
}
