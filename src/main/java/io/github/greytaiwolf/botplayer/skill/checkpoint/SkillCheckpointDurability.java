package io.github.greytaiwolf.botplayer.skill.checkpoint;

/**
 * 检查点的同步落盘边界。
 *
 * <p>{@link net.minecraft.world.level.saveddata.SavedData#setDirty()} 只表示内存对象等待日后保存，
 * 绝不能当作掉电后的恢复授权。实现者只有在本次调用返回后，能够证明当前 SavedData 已经过其
 * 所属存储层的同步保存边界时才能返回；不知道如何同步保存时必须抛出，而不是静默成功。</p>
 */
@FunctionalInterface
public interface SkillCheckpointDurability {
    /**
     * 同步提交当前 checkpoint SavedData。
     *
     * @throws Exception 任一保存、等待或确认失败；调用方会锁死本进程的 checkpoint 恢复路径
     */
    void commit() throws Exception;

    /**
     * 绑定阶段是否已经确认存在真正的同步边界。默认实现适用于正常的、可调用的端口；
     * {@link #unavailable()} 则在任何内存变更前就让 committer 进入失败态。
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * 用于尚未接入世界存储层时的显式拒绝实现。它刻意不是 no-op，防止 setDirty 被误用为
     * “已耐久”。
     */
    static SkillCheckpointDurability unavailable() {
        return new SkillCheckpointDurability() {
            @Override
            public void commit() {
                throw new IllegalStateException(
                        "checkpoint durability port has not been bound to a synchronous store");
            }

            @Override
            public boolean isAvailable() {
                return false;
            }
        };
    }
}
