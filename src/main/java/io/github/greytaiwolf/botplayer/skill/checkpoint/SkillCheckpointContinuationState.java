package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 检查点在持久化时的恢复资格；终态永远不能重新启动。 */
public enum SkillCheckpointContinuationState {
    RESTARTABLE,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PREEMPTED;

    public boolean isTerminal() {
        return this != RESTARTABLE;
    }
}
