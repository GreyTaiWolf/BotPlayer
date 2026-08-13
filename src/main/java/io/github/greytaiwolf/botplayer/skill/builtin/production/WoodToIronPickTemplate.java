package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.List;

/**
 * P5A-5 的最小、有界生产 DAG：橡木 → 木镐 → 石镐 → 铁锭 → 铁镐。
 *
 * <p>该模板不假装采集一定成功，也不绑定任何工作台、熔炉或箱子坐标。采集节点只定义必须由
 * 对应动作技能观察到的精确收益；配方节点只定义将由菜单事务适配器执行和回读的精确 delta；
 * 工作站节点则必须由真实方块放置动作消费相应 block item。不能因为世界里碰巧已有工作台或
 * 熔炉就跳过该闭环。
 */
public final class WoodToIronPickTemplate {
    private WoodToIronPickTemplate() {
    }

    public static ProductionPlanTemplate create() {
        return new ProductionPlanTemplate(
                ProductionSchemas.WOOD_TO_IRON_PICK_V1,
                new ProductionBudget(14, 21, 12, 122, 3),
                List.of(
                        node("harvest_logs", new ResourceAcquisition(
                                AcquisitionMethod.HARVEST_LOG,
                                ProductionLedger.of(
                                        ProductionMaterials.OAK_LOG, 4)),
                                true),
                        node("craft_planks", new RecipeExecution(
                                ProductionRecipes.OAK_LOG_TO_PLANKS, 4),
                                false),
                        node("craft_sticks", new RecipeExecution(
                                ProductionRecipes.OAK_PLANKS_TO_STICKS, 2),
                                false),
                        node("crafting_table", new RecipeExecution(
                                ProductionRecipes.OAK_PLANKS_TO_CRAFTING_TABLE,
                                1), true),
                        node("place_crafting_table", new PlaceWorkstation(
                                WorkstationKind.CRAFTING_TABLE), true),
                        node("wooden_pickaxe", new RecipeExecution(
                                ProductionRecipes.WOODEN_PICKAXE, 1),
                                false),
                        node("mine_cobblestone", new ResourceAcquisition(
                                AcquisitionMethod.MINE_COBBLESTONE,
                                ProductionLedger.of(
                                        ProductionMaterials.COBBLESTONE, 11)),
                                false),
                        node("craft_furnace", new RecipeExecution(
                                ProductionRecipes.COBBLESTONE_TO_FURNACE, 1),
                                false),
                        node("place_furnace", new PlaceWorkstation(
                                WorkstationKind.FURNACE), true),
                        node("stone_pickaxe", new RecipeExecution(
                                ProductionRecipes.STONE_PICKAXE, 1),
                                true),
                        node("mine_raw_iron", new ResourceAcquisition(
                                AcquisitionMethod.MINE_RAW_IRON,
                                ProductionLedger.of(
                                        ProductionMaterials.RAW_IRON, 3)),
                                false),
                        node("mine_coal", new ResourceAcquisition(
                                AcquisitionMethod.MINE_COAL,
                                ProductionLedger.of(
                                        ProductionMaterials.COAL, 1)),
                                false),
                        node("smelt_iron", new RecipeExecution(
                                ProductionRecipes.RAW_IRON_TO_IRON_INGOTS,
                                1), true),
                        node("iron_pickaxe", new RecipeExecution(
                                ProductionRecipes.IRON_PICKAXE, 1), true)),
                List.of(
                        edge("harvest_logs", "craft_planks"),
                        edge("craft_planks", "craft_sticks"),
                        edge("craft_planks", "crafting_table"),
                        edge("crafting_table", "place_crafting_table"),
                        edge("craft_sticks", "wooden_pickaxe"),
                        edge("place_crafting_table", "wooden_pickaxe"),
                        edge("wooden_pickaxe", "mine_cobblestone"),
                        edge("mine_cobblestone", "craft_furnace"),
                        edge("mine_cobblestone", "stone_pickaxe"),
                        edge("place_crafting_table", "craft_furnace"),
                        edge("place_crafting_table", "stone_pickaxe"),
                        edge("craft_sticks", "stone_pickaxe"),
                        edge("craft_furnace", "place_furnace"),
                        edge("stone_pickaxe", "mine_raw_iron"),
                        edge("stone_pickaxe", "mine_coal"),
                        edge("place_furnace", "smelt_iron"),
                        edge("mine_raw_iron", "smelt_iron"),
                        edge("mine_coal", "smelt_iron"),
                        edge("smelt_iron", "iron_pickaxe"),
                        edge("craft_sticks", "iron_pickaxe"),
                        edge("place_crafting_table", "iron_pickaxe")));
    }

    private static ProductionPlanNode node(
            String id, ProductionOperation operation, boolean checkpoint) {
        return new ProductionPlanNode(id, operation, checkpoint);
    }

    private static ProductionPlanEdge edge(String before, String after) {
        return new ProductionPlanEdge(before, after);
    }
}
