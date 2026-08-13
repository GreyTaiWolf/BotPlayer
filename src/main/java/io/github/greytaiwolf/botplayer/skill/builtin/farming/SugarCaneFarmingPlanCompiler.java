package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 编译唯一审核过的“只收获一节最上方原版甘蔗”单节点计划。
 *
 * <p>坐标只是受限目标提示，而非世界事实。服务端节点会重新读取目标、下方基座、上方空气、
 * 原生 InventoryMenu、完整库存和 generation；本编译器没有半径扫描、方块标签或动态脚本
 * 入口。
 */
public final class SugarCaneFarmingPlanCompiler {
    /** Frozen BREAK_BLOCK action. */
    public static final int MAXIMUM_ACTION_TICKS = 160;
    /** Includes the bounded UUID-bound PickupWait continuation after a break. */
    public static final int MAXIMUM_HARVEST_NODE_TICKS = 260;

    private static final String PLAN_DOMAIN =
            "botplayer:sugar-cane-farming-plan:v1";
    private static final String HARVEST_NODE_DOMAIN =
            "botplayer:sugar-cane-farming-harvest-node:v1";
    private static final SkillParameterSchema TARGET_SCHEMA =
            new SkillParameterSchema(Map.of(
                    SugarCaneFarmingSkillIds.TARGET_X_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, -30_000_000, 30_000_000),
                    SugarCaneFarmingSkillIds.TARGET_Y_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, Integer.MIN_VALUE, Integer.MAX_VALUE),
                    SugarCaneFarmingSkillIds.TARGET_Z_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, -30_000_000, 30_000_000)));
    private static final SkillDescriptor HARVEST_DESCRIPTOR =
            new SkillDescriptor(
                    SugarCaneFarmingSkillIds.HARVEST_UPPER_SUGAR_CANE,
                    SugarCaneFarmingSkillIds.VERSION,
                    SkillCategory.RESOURCE,
                    TARGET_SCHEMA,
                    SkillRiskLevel.LOW,
                    java.util.Set.of(),
                    MAXIMUM_HARVEST_NODE_TICKS,
                    0,
                    false);

    private SugarCaneFarmingPlanCompiler() {
    }

    public static SkillDescriptor harvestDescriptor() {
        return HARVEST_DESCRIPTOR;
    }

    public static List<SkillDescriptor> descriptors() {
        return List.of(HARVEST_DESCRIPTOR);
    }

    /** 返回严格的一节点计划；不会附带补种、扫描或额外破坏。 */
    public static SkillPlan compile(
            UUID botId, long revision, BlockCoordinates target) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(target, "target");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        SkillParameters parameters = targetParameters(target);
        return new SkillPlan(
                stableId(PLAN_DOMAIN, botId, revision, target),
                botId,
                revision,
                List.of(new SkillPlanNode(
                        stableId(HARVEST_NODE_DOMAIN, botId, revision,
                                target),
                        SugarCaneFarmingSkillIds.HARVEST_UPPER_SUGAR_CANE,
                        SugarCaneFarmingSkillIds.VERSION,
                        parameters)),
                List.of());
    }

    public static SkillParameters targetParameters(BlockCoordinates target) {
        Objects.requireNonNull(target, "target");
        return new SkillParameters(Map.of(
                SugarCaneFarmingSkillIds.TARGET_X_PARAMETER, target.x(),
                SugarCaneFarmingSkillIds.TARGET_Y_PARAMETER, target.y(),
                SugarCaneFarmingSkillIds.TARGET_Z_PARAMETER, target.z()));
    }

    private static UUID stableId(
            String domain,
            UUID botId,
            long revision,
            BlockCoordinates target) {
        String source = domain
                + "|"
                + botId
                + "|"
                + revision
                + "|"
                + target.x()
                + "|"
                + target.y()
                + "|"
                + target.z();
        UUID value = UUID.nameUUIDFromBytes(source.getBytes(
                StandardCharsets.UTF_8));
        return new UUID(0L, 0L).equals(value)
                ? new UUID(0L, 1L)
                : value;
    }
}
