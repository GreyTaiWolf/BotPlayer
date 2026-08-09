package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 纯恢复判定器仅允许明确拒绝或从安全阶段重新开始。 */
public enum SkillCheckpointRecoveryDisposition {
    REJECT,
    RESTART_FROM_SAFE_PHASE
}
