package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Map;
import java.util.Optional;

/**
 * 1.21.1 P5A 木头到铁镐闭环所需的精确原版配方。
 *
 * <p>选择橡木仅为使账本具备确定性；其它木材变体必须作为独立审核配方加入，不能由
 * 标签或字符串替换自动放行。
 */
public final class ProductionRecipes {
    public static final ProductionRecipeId OAK_LOG_TO_PLANKS =
            new ProductionRecipeId("oak_log_to_planks");
    public static final ProductionRecipeId OAK_PLANKS_TO_STICKS =
            new ProductionRecipeId("oak_planks_to_sticks");
    public static final ProductionRecipeId OAK_PLANKS_TO_CRAFTING_TABLE =
            new ProductionRecipeId("oak_planks_to_crafting_table");
    public static final ProductionRecipeId WOODEN_PICKAXE =
            new ProductionRecipeId("wooden_pickaxe");
    public static final ProductionRecipeId COBBLESTONE_TO_FURNACE =
            new ProductionRecipeId("cobblestone_to_furnace");
    public static final ProductionRecipeId STONE_PICKAXE =
            new ProductionRecipeId("stone_pickaxe");
    public static final ProductionRecipeId RAW_IRON_TO_IRON_INGOTS =
            new ProductionRecipeId("raw_iron_to_iron_ingots");
    public static final ProductionRecipeId IRON_PICKAXE =
            new ProductionRecipeId("iron_pickaxe");

    private ProductionRecipes() {
    }

    public static Map<ProductionRecipeId, ProductionRecipe> all() {
        FurnaceBatchRequirement smeltIron = new FurnaceBatchRequirement(
                ProductionMaterials.RAW_IRON, 3,
                FurnaceFuel.COAL, 1,
                ProductionMaterials.IRON_INGOT, 3);
        return Map.of(
                OAK_LOG_TO_PLANKS, new ProductionRecipe(
                        OAK_LOG_TO_PLANKS,
                        ProductionRecipeKind.INVENTORY_CRAFTING,
                        new ProductionDelta(
                                ProductionLedger.of(
                                        ProductionMaterials.OAK_LOG, 1),
                                ProductionLedger.of(
                                        ProductionMaterials.OAK_PLANKS, 4)),
                        6,
                        Optional.empty()),
                OAK_PLANKS_TO_STICKS, new ProductionRecipe(
                        OAK_PLANKS_TO_STICKS,
                        ProductionRecipeKind.INVENTORY_CRAFTING,
                        new ProductionDelta(
                                ProductionLedger.of(
                                        ProductionMaterials.OAK_PLANKS, 2),
                                ProductionLedger.of(
                                        ProductionMaterials.STICK, 4)),
                        6,
                        Optional.empty()),
                OAK_PLANKS_TO_CRAFTING_TABLE, new ProductionRecipe(
                        OAK_PLANKS_TO_CRAFTING_TABLE,
                        ProductionRecipeKind.INVENTORY_CRAFTING,
                        new ProductionDelta(
                                ProductionLedger.of(
                                        ProductionMaterials.OAK_PLANKS, 4),
                                ProductionLedger.of(
                                        ProductionMaterials.CRAFTING_TABLE, 1)),
                        10,
                        Optional.empty()),
                WOODEN_PICKAXE, new ProductionRecipe(
                        WOODEN_PICKAXE,
                        ProductionRecipeKind.WORKBENCH_CRAFTING,
                        new ProductionDelta(
                                new ProductionLedger(Map.of(
                                        ProductionMaterials.OAK_PLANKS, 3,
                                        ProductionMaterials.STICK, 2)),
                                ProductionLedger.of(
                                        ProductionMaterials.WOODEN_PICKAXE, 1)),
                        16,
                        Optional.empty()),
                COBBLESTONE_TO_FURNACE, new ProductionRecipe(
                        COBBLESTONE_TO_FURNACE,
                        ProductionRecipeKind.WORKBENCH_CRAFTING,
                        new ProductionDelta(
                                ProductionLedger.of(
                                        ProductionMaterials.COBBLESTONE, 8),
                                ProductionLedger.of(
                                        ProductionMaterials.FURNACE, 1)),
                        16,
                        Optional.empty()),
                STONE_PICKAXE, new ProductionRecipe(
                        STONE_PICKAXE,
                        ProductionRecipeKind.WORKBENCH_CRAFTING,
                        new ProductionDelta(
                                new ProductionLedger(Map.of(
                                        ProductionMaterials.COBBLESTONE, 3,
                                        ProductionMaterials.STICK, 2)),
                                ProductionLedger.of(
                                        ProductionMaterials.STONE_PICKAXE, 1)),
                        16,
                        Optional.empty()),
                RAW_IRON_TO_IRON_INGOTS, new ProductionRecipe(
                        RAW_IRON_TO_IRON_INGOTS,
                        ProductionRecipeKind.FURNACE_SMELTING,
                        smeltIron.playerDelta(),
                        12,
                        Optional.of(smeltIron)),
                IRON_PICKAXE, new ProductionRecipe(
                        IRON_PICKAXE,
                        ProductionRecipeKind.WORKBENCH_CRAFTING,
                        new ProductionDelta(
                                new ProductionLedger(Map.of(
                                        ProductionMaterials.IRON_INGOT, 3,
                                        ProductionMaterials.STICK, 2)),
                                ProductionLedger.of(
                                        ProductionMaterials.IRON_PICKAXE, 1)),
                        16,
                        Optional.empty()));
    }
}
