package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 从磁盘读取 checkpoint 时的严格结果，损坏数据绝不降级为可恢复记录。 */
public enum SkillCheckpointLoadStatus {
    VALID,
    MISSING,
    DAMAGED,
    UNSUPPORTED_SCHEMA
}
