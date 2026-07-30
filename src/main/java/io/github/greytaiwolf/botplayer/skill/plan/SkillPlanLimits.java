package io.github.greytaiwolf.botplayer.skill.plan;

/**
 * 服务端可在绝对硬上限内进一步收紧单个 DAG。
 */
public record SkillPlanLimits(
        int maximumNodes,
        int maximumEdges,
        int maximumDepth) {
    public SkillPlanLimits {
        requireRange(
                maximumNodes,
                1,
                SkillPlan.ABSOLUTE_MAX_NODES,
                "maximumNodes");
        requireRange(
                maximumEdges,
                0,
                SkillPlan.ABSOLUTE_MAX_EDGES,
                "maximumEdges");
        requireRange(
                maximumDepth,
                1,
                SkillPlan.ABSOLUTE_MAX_NODES,
                "maximumDepth");
    }

    public static SkillPlanLimits defaults() {
        return new SkillPlanLimits(128, 512, 32);
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
