package io.github.greytaiwolf.botplayer.skill.pack;

/**
 * 包只能从暂存态由管理员绑定当前内容摘要后进入批准态。
 */
public enum SkillPackState {
    STAGED,
    APPROVED,
    REJECTED
}
