package io.github.greytaiwolf.botplayer.skill.runtime;

/**
 * 不依赖 Minecraft 活对象的技能 Tick 时限裁决。
 *
 * <p>硬时限在信号消费前裁决；业务时限只在本 Tick 的合法信号
 * 处理完毕且运行仍然活动时复核。
 */
public final class SkillDeadlinePolicy {
    private SkillDeadlinePolicy() {}

    /**
     * 返回信号消费前必须执行的裁决。
     */
    public static PreSignalDecision beforeSignals(
            long currentTick, long hardDeadlineTick) {
        return currentTick >= hardDeadlineTick
                ? PreSignalDecision.HARD_TIMEOUT
                : PreSignalDecision.PROCESS_SIGNALS;
    }

    /**
     * 判断信号处理后是否应进入业务超时清理。
     *
     * <p>已经存在待提交终态或正在执行补偿动作时，不重复发起
     * 业务超时。
     */
    public static boolean shouldRequestWorkTimeout(
            long currentTick,
            long workDeadlineTick,
            boolean terminalPending,
            boolean cleanupInProgress) {
        return currentTick >= workDeadlineTick
                && !terminalPending
                && !cleanupInProgress;
    }

    public enum PreSignalDecision {
        PROCESS_SIGNALS,
        HARD_TIMEOUT
    }
}
