package io.github.greytaiwolf.botplayer.skill.checkpoint;

/** 同步 checkpoint 提交未获确认时的固定失败，不携带世界路径或原始存档内容。 */
public final class SkillCheckpointDurableCommitException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    SkillCheckpointDurableCommitException(String operation, Throwable cause) {
        super("checkpoint durable commit failed during " + operation, cause);
    }

    SkillCheckpointDurableCommitException(String operation) {
        super("checkpoint durable commits are unavailable after a prior failure during "
                + operation);
    }
}
