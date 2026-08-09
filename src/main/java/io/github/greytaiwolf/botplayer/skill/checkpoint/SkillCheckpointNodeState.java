package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 计划节点在可恢复安全点上的纯数据状态。 */
public enum SkillCheckpointNodeState {
    PENDING,
    READY,
    BLOCKED,
    IN_PROGRESS,
    SUCCEEDED,
    FAILED,
    SKIPPED
}
