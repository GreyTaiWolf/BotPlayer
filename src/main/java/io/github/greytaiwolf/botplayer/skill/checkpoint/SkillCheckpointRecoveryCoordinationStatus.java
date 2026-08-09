package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 重启恢复编排的最终可执行性；判定器拒绝与计划物化失败保持可区分。 */
public enum SkillCheckpointRecoveryCoordinationStatus {
    /** 判定和计划物化均通过，可将 request 交给新 generation 的运行时。 */
    READY,
    /** 身份、代际、审批、瞬态安全性或重新观察未通过判定器。 */
    DECISION_REJECTED,
    /** 判定通过，但当前世界没有可以安全物化的重启计划。 */
    RESTART_PLAN_UNAVAILABLE,
    /** 判定通过，但 provider 给出了非当前批准计划或抛出了异常。 */
    RESTART_PLAN_REJECTED
}
