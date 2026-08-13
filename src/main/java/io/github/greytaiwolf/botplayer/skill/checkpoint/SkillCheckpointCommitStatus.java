package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** checkpoint 仓库在当前进程内是否仍有可证明的同步提交通道。 */
public enum SkillCheckpointCommitStatus {
    /** 最近一次请求（若有）已通过同步提交边界。 */
    READY,
    /** 同步提交失败；本进程不得再恢复、保留或新增 checkpoint。 */
    FAILED
}
