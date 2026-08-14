package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P5B 明确允许的三种原版炉型合同。
 *
 * <p>三者虽然共享 39 槽的原版炉子布局，但绝不是可互换的 {@code AbstractFurnaceMenu}：每个值
 * 同时冻结对应方块、服务端配方类型和最小等待预算。Minecraft 适配器还必须将它与精确 Mojang
 * menu class 成对验证；本枚举不把槽位数或父类继承关系当作身份。
 */
public enum FurnaceKind {
    FURNACE(
            "furnace",
            "minecraft:furnace",
            "minecraft:smelting",
            700L),
    BLAST_FURNACE(
            "blast_furnace",
            "minecraft:blast_furnace",
            "minecraft:blasting",
            400L),
    SMOKER(
            "smoker",
            "minecraft:smoker",
            "minecraft:smoking",
            200L);

    private static final Set<String> HORIZONTAL_FACINGS = Set.of(
            "north", "south", "west", "east");
    private static final Set<String> BOOLEAN_VALUES = Set.of(
            "false", "true");
    private static final Set<String> PROPERTY_NAMES = Set.of(
            "facing", "lit");

    private final String stableId;
    private final ResourceId blockId;
    private final String recipeTypeStableId;
    private final long minimumTransactionTicks;

    FurnaceKind(
            String stableId,
            String blockId,
            String recipeTypeStableId,
            long minimumTransactionTicks) {
        if (stableId == null || stableId.isBlank()) {
            throw new IllegalArgumentException(
                    "furnace kind stable id must be non-blank");
        }
        if (recipeTypeStableId == null || recipeTypeStableId.isBlank()) {
            throw new IllegalArgumentException(
                    "furnace recipe type id must be non-blank");
        }
        if (minimumTransactionTicks < 1L) {
            throw new IllegalArgumentException(
                    "furnace transaction minimum must be positive");
        }
        this.stableId = stableId;
        this.blockId = new ResourceId(blockId);
        this.recipeTypeStableId = recipeTypeStableId;
        this.minimumTransactionTicks = minimumTransactionTicks;
    }

    public String stableId() {
        return stableId;
    }

    public ResourceId blockId() {
        return blockId;
    }

    /**
     * 审计/诊断用的原版 {@code RecipeType} 稳定名称；实际适配器仍使用精确 Java 常量查询。
     */
    public String recipeTypeStableId() {
        return recipeTypeStableId;
    }

    /**
     * 该炉型完整生产动作的保守最小时限，包含真实点击、关闭和轮询余量。
     */
    public long minimumTransactionTicks() {
        return minimumTransactionTicks;
    }

    /**
     * 所有三种原版炉型均必须呈现完整的 {@code facing + lit} block-state；只有 {@code lit}
     * 可以在已绑定的熔炼运行期间变化。
     */
    public boolean matchesWorkstationState(BlockStateFingerprint state) {
        Objects.requireNonNull(state, "state");
        Map<String, String> properties = state.properties();
        return blockId.equals(state.blockId())
                && properties.keySet().equals(PROPERTY_NAMES)
                && HORIZONTAL_FACINGS.contains(properties.get("facing"))
                && BOOLEAN_VALUES.contains(properties.get("lit"));
    }

    /**
     * 用于原版放置后回读的非点燃完整 state。调用方必须先从真实 placement context 得到水平朝向。
     */
    public BlockStateFingerprint expectedPlacedState(String horizontalFacing) {
        Objects.requireNonNull(horizontalFacing, "horizontalFacing");
        if (!HORIZONTAL_FACINGS.contains(horizontalFacing)) {
            throw new IllegalArgumentException(
                    "furnace placement facing must be horizontal");
        }
        return new BlockStateFingerprint(blockId, Map.of(
                "facing", horizontalFacing,
                "lit", "false"));
    }
}
