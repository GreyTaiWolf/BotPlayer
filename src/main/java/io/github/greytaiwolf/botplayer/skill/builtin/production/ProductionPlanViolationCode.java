package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 任何一项都使生产计划拒绝，调用方不得挑选性忽略。
 */
public enum ProductionPlanViolationCode {
    UNKNOWN_SCHEMA,
    DUPLICATE_NODE_ID,
    DUPLICATE_EDGE,
    UNKNOWN_EDGE_ENDPOINT,
    CYCLIC_DAG,
    UNKNOWN_RECIPE,
    RECIPE_NOT_ALLOWED,
    ACQUISITION_NOT_ALLOWED,
    ACQUISITION_METHOD_MISMATCH,
    OPERATION_OUTSIDE_LIMIT,
    UNSATISFIED_LEDGER,
    BUDGET_EXCEEDED,
    FINAL_OUTPUT_MISSING
}
