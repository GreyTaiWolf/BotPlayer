package io.github.greytaiwolf.botplayer.skill.task;

/**
 * 单次查询声明的工作与证据上限。服务层还会施加每 Tick 的更低总额度。
 */
public record TaskSensorBudget(
        int maximumCandidates,
        int maximumSlots,
        int maximumBlocks,
        int maximumRays,
        int maximumEvidence,
        long maximumAgeTicks) {
    public static final int MAX_CANDIDATES = 128;
    public static final int MAX_SLOTS = 128;
    public static final int MAX_BLOCKS = 256;
    public static final int MAX_RAYS = 32;
    public static final int MAX_EVIDENCE = 256;
    public static final long MAX_AGE_TICKS = 1_200L;

    public TaskSensorBudget {
        requireRange(
                maximumCandidates, 0, MAX_CANDIDATES, "maximumCandidates");
        requireRange(maximumSlots, 0, MAX_SLOTS, "maximumSlots");
        requireRange(maximumBlocks, 0, MAX_BLOCKS, "maximumBlocks");
        requireRange(maximumRays, 0, MAX_RAYS, "maximumRays");
        requireRange(maximumEvidence, 0, MAX_EVIDENCE, "maximumEvidence");
        if (maximumAgeTicks < 0L
                || maximumAgeTicks > MAX_AGE_TICKS) {
            throw new IllegalArgumentException(
                    "maximumAgeTicks must be between 0 and "
                            + MAX_AGE_TICKS);
        }
    }

    public int workUnits() {
        return maximumCandidates
                + maximumSlots
                + maximumBlocks
                + maximumRays
                + maximumEvidence;
    }

    public boolean supports(TaskSensorQueryType type) {
        java.util.Objects.requireNonNull(type, "type");
        return (type.permitsCandidates() || maximumCandidates == 0)
                && (type.permitsSlots() || maximumSlots == 0)
                && (type.permitsBlocks() || maximumBlocks == 0)
                && (type.permitsRays() || maximumRays == 0);
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
