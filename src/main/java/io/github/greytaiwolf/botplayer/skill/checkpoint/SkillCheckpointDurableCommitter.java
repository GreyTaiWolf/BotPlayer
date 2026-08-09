package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 将 checkpoint 内容变更与外部同步提交串成一个 fail-closed 的操作。
 *
 * <p>底层 {@link SkillCheckpointSavedData} 仍负责 schema、代际与 revision 单调性；本类只
 * 增加“这次变更已跨过可注入的同步存储边界”这一授权。一次提交失败后内存可能已经领先于磁盘，
 * 因而实例会永久锁死，调用方必须取消当前 run，并且不得使用内存记录启动恢复或在停服时保留它。</p>
 */
public final class SkillCheckpointDurableCommitter {
    private final SkillCheckpointSavedData checkpoints;
    private final SkillCheckpointDurability durability;
    private SkillCheckpointCommitStatus status;
    private String failedOperation;

    public SkillCheckpointDurableCommitter(
            SkillCheckpointSavedData checkpoints,
            SkillCheckpointDurability durability) {
        this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
        this.durability = Objects.requireNonNull(durability, "durability");
        if (durability.isAvailable()) {
            status = SkillCheckpointCommitStatus.READY;
        } else {
            status = SkillCheckpointCommitStatus.FAILED;
            failedOperation = "durability_unavailable";
        }
    }

    public SkillCheckpointCommitStatus status() {
        return status;
    }

    /** 仅 READY 可作为恢复入队或正常停服 checkpoint 保留的前提。 */
    public boolean isReady() {
        return status == SkillCheckpointCommitStatus.READY;
    }

    /** 同时写入内存仓库并等待外部同步提交。 */
    public void upsert(SkillCheckpoint checkpoint) {
        requireReady("upsert");
        checkpoints.upsert(checkpoint);
        commit("upsert");
    }

    /** 删除当前 Bot checkpoint；若有实际删除也必须同步提交。 */
    public Optional<SkillCheckpoint> remove(UUID botId) {
        requireReady("remove");
        Optional<SkillCheckpoint> removed = checkpoints.remove(botId);
        if (removed.isPresent()) {
            commit("remove");
        }
        return removed;
    }

    /** 仅删除精确 generation，且实际删除后必须同步提交。 */
    public boolean closeGeneration(UUID botId, long generation) {
        requireReady("close_generation");
        boolean closed = checkpoints.closeGeneration(botId, generation);
        if (closed) {
            commit("close_generation");
        }
        return closed;
    }

    /**
     * 在没有内容变更但调用方仍需重新取得落盘证据时使用。它不会把 setDirty 当作成功。
     */
    public void confirmCurrentState() {
        requireReady("confirm");
        commit("confirm");
    }

    private void requireReady(String operation) {
        if (!isReady()) {
            throw new SkillCheckpointDurableCommitException(
                    failedOperation == null ? operation : failedOperation);
        }
    }

    private void commit(String operation) {
        try {
            durability.commit();
        } catch (Exception exception) {
            status = SkillCheckpointCommitStatus.FAILED;
            failedOperation = operation;
            throw new SkillCheckpointDurableCommitException(operation, exception);
        }
    }
}
