package io.github.greytaiwolf.botplayer.lifecycle.retirement;

/** 纯模型可稳定诊断的 generation 退役失败类别。 */
public enum GenerationRetirementFailure {
    NONE,
    OBSERVED_UNSAFE,
    DEADLINE_EXCEEDED,
    ATTEMPT_BUDGET_EXHAUSTED,
    AUTHORITY_CONFLICT
}
