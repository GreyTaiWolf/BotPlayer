package io.github.greytaiwolf.botplayer.skill.task;

/**
 * 全服和单 run 的每 Tick 预算以及缓存上限，防止任务感知退化为无限扫描。
 */
public record TaskSensorLimits(
        int maximumSamplesPerTick,
        int maximumSamplesPerRunPerTick,
        int maximumWorkUnitsPerTick,
        int maximumWorkUnitsPerRunPerTick,
        int maximumCachedSnapshots,
        long maximumCacheAgeTicks) {
    public static final int MAX_SAMPLES_PER_TICK = 4_096;
    public static final int MAX_SAMPLES_PER_RUN_PER_TICK = 256;
    public static final int MAX_WORK_UNITS_PER_TICK = 65_536;
    public static final int MAX_WORK_UNITS_PER_RUN_PER_TICK = 16_384;
    public static final int MAX_CACHED_SNAPSHOTS = 65_536;

    public TaskSensorLimits {
        requireRange(
                maximumSamplesPerTick,
                1,
                MAX_SAMPLES_PER_TICK,
                "maximumSamplesPerTick");
        requireRange(
                maximumSamplesPerRunPerTick,
                1,
                MAX_SAMPLES_PER_RUN_PER_TICK,
                "maximumSamplesPerRunPerTick");
        requireRange(
                maximumWorkUnitsPerTick,
                0,
                MAX_WORK_UNITS_PER_TICK,
                "maximumWorkUnitsPerTick");
        requireRange(
                maximumWorkUnitsPerRunPerTick,
                0,
                MAX_WORK_UNITS_PER_RUN_PER_TICK,
                "maximumWorkUnitsPerRunPerTick");
        requireRange(
                maximumCachedSnapshots,
                1,
                MAX_CACHED_SNAPSHOTS,
                "maximumCachedSnapshots");
        if (maximumCacheAgeTicks < 0L
                || maximumCacheAgeTicks
                        > TaskSensorBudget.MAX_AGE_TICKS) {
            throw new IllegalArgumentException(
                    "maximumCacheAgeTicks must be between 0 and "
                            + TaskSensorBudget.MAX_AGE_TICKS);
        }
    }

    public static TaskSensorLimits defaults() {
        return new TaskSensorLimits(64, 8, 4_096, 512, 4_096, 200L);
    }

    private static void requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name
                            + " must be between "
                            + minimum
                            + " and "
                            + maximum);
        }
    }
}
