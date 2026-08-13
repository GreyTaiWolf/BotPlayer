package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 当前版本公开的、可执行的生产 schema。
 */
public final class ProductionSchemas {
    public static final ProductionSchema WOOD_TO_IRON_PICK_V1 =
            new ProductionSchema(
                    "wood_to_iron_pick",
                    1,
                    Set.of(
                            ProductionRecipes.OAK_LOG_TO_PLANKS,
                            ProductionRecipes.OAK_PLANKS_TO_STICKS,
                            ProductionRecipes.OAK_PLANKS_TO_CRAFTING_TABLE,
                            ProductionRecipes.WOODEN_PICKAXE,
                            ProductionRecipes.COBBLESTONE_TO_FURNACE,
                            ProductionRecipes.STONE_PICKAXE,
                            ProductionRecipes.RAW_IRON_TO_IRON_INGOTS,
                            ProductionRecipes.IRON_PICKAXE),
                    Set.of(
                            ProductionMaterials.OAK_LOG,
                            ProductionMaterials.COBBLESTONE,
                            ProductionMaterials.RAW_IRON,
                            ProductionMaterials.COAL),
                    ProductionLedger.of(ProductionMaterials.IRON_PICKAXE, 1),
                    new ProductionBudget(20, 32, 16, 192, 16));

    private static final Map<String, ProductionSchema> KNOWN = Map.of(
            key(WOOD_TO_IRON_PICK_V1.schemaId(),
                    WOOD_TO_IRON_PICK_V1.version()),
            WOOD_TO_IRON_PICK_V1);

    private ProductionSchemas() {
    }

    /**
     * 仅当输入与编译进来的 schema 完全相同才认可，避免同名 schema 被悄悄放宽。
     */
    public static boolean isKnownExact(ProductionSchema schema) {
        return find(schema.schemaId(), schema.version())
                .filter(schema::equals)
                .isPresent();
    }

    public static Optional<ProductionSchema> find(String schemaId, int version) {
        return Optional.ofNullable(KNOWN.get(key(schemaId, version)));
    }

    private static String key(String schemaId, int version) {
        return schemaId + "@" + version;
    }
}
