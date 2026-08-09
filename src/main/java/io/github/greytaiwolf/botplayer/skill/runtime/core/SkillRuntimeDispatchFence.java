package io.github.greytaiwolf.botplayer.skill.runtime.core;

import java.util.Objects;

/**
 * 在节点处理器可能把原版动作交给异步 mailbox 之前取得的耐久许可。
 *
 * <p>通用 runtime 不知道 checkpoint 的存储实现；它只在每个节点开始前把当前纯
 * {@link SkillRuntimeCheckpoint} 交给这个窄端口。拒绝时处理器尚未被调用，因而没有
 * menu、方块、输入或 action 副作用需要回滚。默认端口仅供不使用 P5A 恢复的纯单测；
 * Minecraft 生命周期必须注入真正的同步持久化 fence。</p>
 */
@FunctionalInterface
public interface SkillRuntimeDispatchFence {
    /**
     * 返回允许才可调用节点 {@code begin()}。实现必须 fail closed：无法证明旧恢复点已被
     * 撤销时返回拒绝，不能把内存 {@code setDirty()} 当成授权。
     */
    Result beforeNodeDispatch(
            SkillRuntimeCheckpoint checkpoint, long currentTick);

    /** 仅用于没有持久恢复语义的纯 runtime 使用方。 */
    static SkillRuntimeDispatchFence permissive() {
        return (checkpoint, currentTick) -> Result.permit();
    }

    /** 不携带存档、动作或活对象的有限结果。 */
    record Result(boolean permitted, String safeSummary) {
        private static final int MAX_SUMMARY_LENGTH = 256;

        public Result {
            if (safeSummary == null
                    || safeSummary.isBlank()
                    || safeSummary.length() > MAX_SUMMARY_LENGTH
                    || !safeSummary.equals(safeSummary.strip())
                    || safeSummary.codePoints()
                            .anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "dispatch fence summary must be a bounded safe string");
            }
        }

        public static Result permit() {
            return new Result(true, "技能节点已取得派发许可");
        }

        public static Result reject(String safeSummary) {
            return new Result(false, Objects.requireNonNull(
                    safeSummary, "safeSummary"));
        }
    }
}
