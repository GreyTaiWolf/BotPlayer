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
        /*
         * P5A canonical bootstrap 在每个资源物理 fragment 前都有一张有界导航门。最长
         * 生产链从原来的 32 层增至 50 层；51 只给这一份已审核 DAG 留一层静态余量，仍远低于
         * 绝对节点上限。
         */
        return new SkillPlanLimits(128, 512, 51);
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
