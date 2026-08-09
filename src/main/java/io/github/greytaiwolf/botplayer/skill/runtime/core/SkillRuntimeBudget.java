package io.github.greytaiwolf.botplayer.skill.runtime.core;

/**
 * 通用技能运行时的固定上限。所有值均在服务端启动时确定，避免技能包扩大
 * 每 tick 的工作量或保留无限期运行。
 */
public record SkillRuntimeBudget(
        int maximumActiveRuns,
        int maximumSignalsPerRunTick,
        int maximumRetainedViews,
        int maximumRunTicks) {
    public static final int ABSOLUTE_MAX_ACTIVE_RUNS = 4_096;
    public static final int ABSOLUTE_MAX_SIGNALS_PER_TICK = 128;
    public static final int ABSOLUTE_MAX_RETAINED_VIEWS = 16_384;

    public SkillRuntimeBudget {
        if (maximumActiveRuns < 1
                || maximumActiveRuns > ABSOLUTE_MAX_ACTIVE_RUNS) {
            throw new IllegalArgumentException(
                    "maximumActiveRuns must be between 1 and "
                            + ABSOLUTE_MAX_ACTIVE_RUNS);
        }
        if (maximumSignalsPerRunTick < 1
                || maximumSignalsPerRunTick
                        > ABSOLUTE_MAX_SIGNALS_PER_TICK) {
            throw new IllegalArgumentException(
                    "maximumSignalsPerRunTick must be between 1 and "
                            + ABSOLUTE_MAX_SIGNALS_PER_TICK);
        }
        if (maximumRetainedViews < 1
                || maximumRetainedViews
                        > ABSOLUTE_MAX_RETAINED_VIEWS) {
            throw new IllegalArgumentException(
                    "maximumRetainedViews must be between 1 and "
                            + ABSOLUTE_MAX_RETAINED_VIEWS);
        }
        if (maximumRunTicks < 1
                || maximumRunTicks > 1_728_000) {
            throw new IllegalArgumentException(
                    "maximumRunTicks must be between 1 and 1728000");
        }
    }

    public static SkillRuntimeBudget defaults() {
        return new SkillRuntimeBudget(256, 16, 1_024, 72_000);
    }
}
