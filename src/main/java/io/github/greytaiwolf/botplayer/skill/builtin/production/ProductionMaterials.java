package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * P5A 木头到铁镐闭环明确允许的原版材料。
 *
 * <p>这里故意不把“任意原木”“任意燃料”做成模糊类别。扩展材料必须显式增加配方、
 * schema 和测试，避免由模组物品或未知标签意外进入生产闭环。
 */
public final class ProductionMaterials {
    public static final ProductionMaterial OAK_LOG =
            ProductionMaterial.minecraft("oak_log");
    public static final ProductionMaterial OAK_PLANKS =
            ProductionMaterial.minecraft("oak_planks");
    public static final ProductionMaterial STICK =
            ProductionMaterial.minecraft("stick");
    public static final ProductionMaterial CRAFTING_TABLE =
            ProductionMaterial.minecraft("crafting_table");
    public static final ProductionMaterial WOODEN_PICKAXE =
            ProductionMaterial.minecraft("wooden_pickaxe");
    public static final ProductionMaterial COBBLESTONE =
            ProductionMaterial.minecraft("cobblestone");
    public static final ProductionMaterial FURNACE =
            ProductionMaterial.minecraft("furnace");
    public static final ProductionMaterial BLAST_FURNACE =
            ProductionMaterial.minecraft("blast_furnace");
    public static final ProductionMaterial SMOKER =
            ProductionMaterial.minecraft("smoker");
    public static final ProductionMaterial STONE_PICKAXE =
            ProductionMaterial.minecraft("stone_pickaxe");
    public static final ProductionMaterial RAW_IRON =
            ProductionMaterial.minecraft("raw_iron");
    public static final ProductionMaterial COAL =
            ProductionMaterial.minecraft("coal");
    public static final ProductionMaterial CHARCOAL =
            ProductionMaterial.minecraft("charcoal");
    public static final ProductionMaterial IRON_INGOT =
            ProductionMaterial.minecraft("iron_ingot");
    public static final ProductionMaterial IRON_PICKAXE =
            ProductionMaterial.minecraft("iron_pickaxe");

    private ProductionMaterials() {
    }
}
