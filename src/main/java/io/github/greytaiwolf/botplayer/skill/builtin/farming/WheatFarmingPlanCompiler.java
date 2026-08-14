package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * 编译唯一审核过的“成熟小麦收获后原地补种”双节点计划。
 *
 * <p>坐标是受限目标提示而不是世界事实：两个节点都会在服务器线程重新读取方块、耕地、
 * 光照、菜单和库存。该编译器不提供通用作物、半径扫描或动态脚本入口。
 */
public final class WheatFarmingPlanCompiler {
    /** One frozen BREAK_BLOCK action or one frozen USE_ON_BLOCK action. */
    public static final int MAXIMUM_ACTION_TICKS = 160;
    /**
     * Harvest includes a single bounded UUID-bound {@code PickupWait} after the
     * original block break.  The action itself remains finite; the descriptor
     * budget accounts for both stages so the runtime cannot silently truncate the
     * collection proof before planting begins.
     */
    public static final int MAXIMUM_HARVEST_NODE_TICKS = 260;
    public static final int MAXIMUM_PLANT_NODE_TICKS = MAXIMUM_ACTION_TICKS;

    private static final String PLAN_DOMAIN =
            "botplayer:wheat-farming-plan:v1";
    private static final String HARVEST_NODE_DOMAIN =
            "botplayer:wheat-farming-harvest-node:v1";
    private static final String PLANT_NODE_DOMAIN =
            "botplayer:wheat-farming-plant-node:v1";
    private static final SkillParameterSchema TARGET_SCHEMA =
            new SkillParameterSchema(Map.of(
                    WheatFarmingSkillIds.TARGET_X_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, -30_000_000, 30_000_000),
                    WheatFarmingSkillIds.TARGET_Y_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, Integer.MIN_VALUE, Integer.MAX_VALUE),
                    WheatFarmingSkillIds.TARGET_Z_PARAMETER,
                    new SkillParameterRule.IntegerRule(
                            true, -30_000_000, 30_000_000)));
    private static final SkillDescriptor HARVEST_DESCRIPTOR =
            descriptor(WheatFarmingSkillIds.HARVEST_MATURE_WHEAT);
    private static final SkillDescriptor PLANT_DESCRIPTOR =
            descriptor(WheatFarmingSkillIds.PLANT_WHEAT);

    private WheatFarmingPlanCompiler() {
    }

    public static SkillDescriptor harvestDescriptor() {
        return HARVEST_DESCRIPTOR;
    }

    public static SkillDescriptor plantDescriptor() {
        return PLANT_DESCRIPTOR;
    }

    public static List<SkillDescriptor> descriptors() {
        return List.of(HARVEST_DESCRIPTOR, PLANT_DESCRIPTOR);
    }

    /** 返回只含 harvest → plant 依赖的固定双节点 DAG。 */
    public static SkillPlan compile(
            UUID botId, long revision, BlockCoordinates target) {
        Objects.requireNonNull(botId, "botId");
        Objects.requireNonNull(target, "target");
        if (revision < 1L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        SkillParameters parameters = targetParameters(target);
        UUID planId = stableId(PLAN_DOMAIN, botId, revision, target);
        UUID harvestId = stableId(HARVEST_NODE_DOMAIN, botId, revision,
                target);
        UUID plantId = stableId(PLANT_NODE_DOMAIN, botId, revision, target);
        return new SkillPlan(
                planId,
                botId,
                revision,
                List.of(
                        new SkillPlanNode(
                                harvestId,
                                WheatFarmingSkillIds.HARVEST_MATURE_WHEAT,
                                WheatFarmingSkillIds.VERSION,
                                parameters),
                        new SkillPlanNode(
                                plantId,
                                WheatFarmingSkillIds.PLANT_WHEAT,
                                WheatFarmingSkillIds.VERSION,
                                parameters)),
                List.of(new SkillPlanEdge(harvestId, plantId)));
    }

    public static SkillParameters targetParameters(BlockCoordinates target) {
        Objects.requireNonNull(target, "target");
        return new SkillParameters(Map.of(
                WheatFarmingSkillIds.TARGET_X_PARAMETER, target.x(),
                WheatFarmingSkillIds.TARGET_Y_PARAMETER, target.y(),
                WheatFarmingSkillIds.TARGET_Z_PARAMETER, target.z()));
    }

    private static SkillDescriptor descriptor(
            io.github.greytaiwolf.botplayer.skill.core.SkillId id) {
        return new SkillDescriptor(
                id,
                WheatFarmingSkillIds.VERSION,
                SkillCategory.RESOURCE,
                TARGET_SCHEMA,
                SkillRiskLevel.LOW,
                java.util.Set.of(),
                id.equals(WheatFarmingSkillIds.HARVEST_MATURE_WHEAT)
                        ? MAXIMUM_HARVEST_NODE_TICKS
                        : MAXIMUM_PLANT_NODE_TICKS,
                0,
                false);
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
