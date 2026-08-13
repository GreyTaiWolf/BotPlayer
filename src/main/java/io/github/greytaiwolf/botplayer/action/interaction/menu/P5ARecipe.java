package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuSlotRole;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * P5A 生产闭环可在原版菜单中执行的封闭配方合同。
 *
 * <p>这不是数据包 recipe id 的透传，也不接受标签、配方 JSON 或任意物品替换。每个条目
 * 同时固定菜单族、输入格、投入数量、燃料（若需要）和默认原版产物；适配器必须在真实
 * {@code clicked()} 前后逐槽证明这份合同。
 */
public enum P5ARecipe {
    OAK_LOG_TO_PLANKS(
            "oak_log_to_planks",
            MenuFamily.INVENTORY_2X2,
            List.of(ingredient(1, "minecraft:oak_log")),
            "minecraft:oak_planks",
            4,
            64),
    OAK_PLANKS_TO_STICKS(
            "oak_planks_to_sticks",
            MenuFamily.INVENTORY_2X2,
            List.of(
                    ingredient(1, "minecraft:oak_planks"),
                    ingredient(3, "minecraft:oak_planks")),
            "minecraft:stick",
            4,
            64),
    OAK_PLANKS_TO_CRAFTING_TABLE(
            "oak_planks_to_crafting_table",
            MenuFamily.INVENTORY_2X2,
            List.of(
                    ingredient(1, "minecraft:oak_planks"),
                    ingredient(2, "minecraft:oak_planks"),
                    ingredient(3, "minecraft:oak_planks"),
                    ingredient(4, "minecraft:oak_planks")),
            "minecraft:crafting_table",
            1,
            64),
    WOODEN_PICKAXE(
            "wooden_pickaxe",
            MenuFamily.CRAFTING_3X3,
            List.of(
                    ingredient(1, "minecraft:oak_planks"),
                    ingredient(2, "minecraft:oak_planks"),
                    ingredient(3, "minecraft:oak_planks"),
                    ingredient(5, "minecraft:stick"),
                    ingredient(8, "minecraft:stick")),
            "minecraft:wooden_pickaxe",
            1,
            1),
    COBBLESTONE_TO_FURNACE(
            "cobblestone_to_furnace",
            MenuFamily.CRAFTING_3X3,
            List.of(
                    ingredient(1, "minecraft:cobblestone"),
                    ingredient(2, "minecraft:cobblestone"),
                    ingredient(3, "minecraft:cobblestone"),
                    ingredient(4, "minecraft:cobblestone"),
                    ingredient(6, "minecraft:cobblestone"),
                    ingredient(7, "minecraft:cobblestone"),
                    ingredient(8, "minecraft:cobblestone"),
                    ingredient(9, "minecraft:cobblestone")),
            "minecraft:furnace",
            1,
            64),
    STONE_PICKAXE(
            "stone_pickaxe",
            MenuFamily.CRAFTING_3X3,
            List.of(
                    ingredient(1, "minecraft:cobblestone"),
                    ingredient(2, "minecraft:cobblestone"),
                    ingredient(3, "minecraft:cobblestone"),
                    ingredient(5, "minecraft:stick"),
                    ingredient(8, "minecraft:stick")),
            "minecraft:stone_pickaxe",
            1,
            1),
    RAW_IRON_TO_IRON_INGOTS(
            "raw_iron_to_iron_ingots",
            "minecraft:raw_iron",
            3,
            "minecraft:coal",
            1,
            "minecraft:iron_ingot",
            3,
            64),
    IRON_PICKAXE(
            "iron_pickaxe",
            MenuFamily.CRAFTING_3X3,
            List.of(
                    ingredient(1, "minecraft:iron_ingot"),
                    ingredient(2, "minecraft:iron_ingot"),
                    ingredient(3, "minecraft:iron_ingot"),
                    ingredient(5, "minecraft:stick"),
                    ingredient(8, "minecraft:stick")),
            "minecraft:iron_pickaxe",
            1,
            1);

    private final String stableId;
    private final MenuFamily family;
    private final List<Ingredient> ingredients;
    private final ResourceId furnaceInput;
    private final int furnaceInputCount;
    private final ResourceId furnaceFuel;
    private final int furnaceFuelCount;
    private final ResourceId output;
    private final int outputCount;
    private final int outputMaxStackSize;

    P5ARecipe(
            String stableId,
            MenuFamily family,
            List<Ingredient> ingredients,
            String output,
            int outputCount,
            int outputMaxStackSize) {
        this.stableId = requireStableId(stableId);
        this.family = requireCraftingFamily(family);
        this.ingredients = immutableIngredients(this.family, ingredients);
        furnaceInput = null;
        furnaceInputCount = 0;
        furnaceFuel = null;
        furnaceFuelCount = 0;
        this.output = new ResourceId(output);
        this.outputCount = requirePositive(outputCount, "outputCount");
        this.outputMaxStackSize = requireOutputStackSize(
                outputMaxStackSize, this.outputCount);
    }

    P5ARecipe(
            String stableId,
            String furnaceInput,
            int furnaceInputCount,
            String furnaceFuel,
            int furnaceFuelCount,
            String output,
            int outputCount,
            int outputMaxStackSize) {
        this.stableId = requireStableId(stableId);
        family = MenuFamily.FURNACE;
        ingredients = List.of();
        this.furnaceInput = new ResourceId(furnaceInput);
        this.furnaceInputCount = requirePositive(
                furnaceInputCount, "furnaceInputCount");
        this.furnaceFuel = new ResourceId(furnaceFuel);
        this.furnaceFuelCount = requirePositive(
                furnaceFuelCount, "furnaceFuelCount");
        this.output = new ResourceId(output);
        this.outputCount = requirePositive(outputCount, "outputCount");
        this.outputMaxStackSize = requireOutputStackSize(
                outputMaxStackSize, this.outputCount);
        if (this.furnaceInput.equals(this.furnaceFuel)
                || this.furnaceInput.equals(this.output)
                || this.furnaceFuel.equals(this.output)) {
            throw new IllegalArgumentException(
                    "furnace contract materials must be distinct");
        }
    }

    public String stableId() {
        return stableId;
    }

    public MenuFamily family() {
        return family;
    }

    public List<Ingredient> ingredients() {
        return ingredients;
    }

    public ResourceId output() {
        return output;
    }

    public int outputCount() {
        return outputCount;
    }

    public int outputMaxStackSize() {
        return outputMaxStackSize;
    }

    /**
     * 当前 P5A canonical DAG 审核过的单个 recipe action 最大批数。这个限制同时避免把
     * {@link io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits#HARD_MAX_CLICKS}
     * 当成任意批量生产许可。
     */
    public int maximumBatches() {
        return switch (this) {
            case OAK_LOG_TO_PLANKS -> 4;
            case OAK_PLANKS_TO_STICKS -> 2;
            default -> 1;
        };
    }

    public boolean isCrafting() {
        return family == MenuFamily.INVENTORY_2X2
                || family == MenuFamily.CRAFTING_3X3;
    }

    public boolean isFurnace() {
        return family == MenuFamily.FURNACE;
    }

    public ResourceId furnaceInput() {
        requireFurnace();
        return furnaceInput;
    }

    public int furnaceInputCount() {
        requireFurnace();
        return furnaceInputCount;
    }

    public ResourceId furnaceFuel() {
        requireFurnace();
        return furnaceFuel;
    }

    public int furnaceFuelCount() {
        requireFurnace();
        return furnaceFuelCount;
    }

    /**
     * 返回此合同中所有需要默认原版组件指纹的物品身份。
     */
    public List<ResourceId> materialIds() {
        Map<ResourceId, Boolean> ordered = new LinkedHashMap<>();
        for (Ingredient ingredient : ingredients) {
            ordered.put(ingredient.item(), Boolean.TRUE);
        }
        if (isFurnace()) {
            ordered.put(furnaceInput, Boolean.TRUE);
            ordered.put(furnaceFuel, Boolean.TRUE);
        }
        ordered.put(output, Boolean.TRUE);
        return List.copyOf(ordered.keySet());
    }

    public static Optional<P5ARecipe> find(String stableId) {
        if (stableId == null) {
            return Optional.empty();
        }
        for (P5ARecipe recipe : values()) {
            if (recipe.stableId.equals(stableId)) {
                return Optional.of(recipe);
            }
        }
        return Optional.empty();
    }

    private static Ingredient ingredient(int slot, String item) {
        return new Ingredient(slot, new ResourceId(item), 1);
    }

    private static String requireStableId(String stableId) {
        Objects.requireNonNull(stableId, "stableId");
        if (stableId.isBlank() || stableId.length() > 80) {
            throw new IllegalArgumentException(
                    "recipe stable id must be a bounded non-blank value");
        }
        return stableId;
    }

    private static MenuFamily requireCraftingFamily(MenuFamily family) {
        Objects.requireNonNull(family, "family");
        if (family != MenuFamily.INVENTORY_2X2
                && family != MenuFamily.CRAFTING_3X3) {
            throw new IllegalArgumentException(
                    "crafting recipe must use an exact crafting menu family");
        }
        return family;
    }

    private static List<Ingredient> immutableIngredients(
            MenuFamily family, List<Ingredient> ingredients) {
        Objects.requireNonNull(ingredients, "ingredients");
        if (ingredients.isEmpty()) {
            throw new IllegalArgumentException(
                    "crafting recipe must have ingredients");
        }
        Map<Integer, Boolean> seenSlots = new LinkedHashMap<>();
        List<Ingredient> copied = List.copyOf(ingredients);
        for (Ingredient ingredient : copied) {
            Objects.requireNonNull(ingredient, "ingredient");
            family.requireSlot(ingredient.slot());
            if (family.roleAt(ingredient.slot())
                    != MenuSlotRole.CRAFTING_INPUT
                    || seenSlots.put(ingredient.slot(), Boolean.TRUE)
                            != null) {
                throw new IllegalArgumentException(
                        "crafting ingredients must occupy unique crafting input slots");
            }
        }
        return copied;
    }

    private static int requirePositive(int value, String name) {
        if (value < 1 || value > 64) {
            throw new IllegalArgumentException(
                    name + " must be in 1..64");
        }
        return value;
    }

    private static int requireOutputStackSize(
            int outputMaxStackSize, int outputCount) {
        if (outputMaxStackSize < outputCount
                || outputMaxStackSize > 64) {
            throw new IllegalArgumentException(
                    "output stack size must cover one bounded recipe output");
        }
        return outputMaxStackSize;
    }

    private void requireFurnace() {
        if (!isFurnace()) {
            throw new IllegalStateException(
                    "crafting recipe has no furnace material contract");
        }
    }

    /**
     * 一个精确格位的单种原版材料。P5A 不表达标签、替代输入或模糊形状。
     */
    public record Ingredient(int slot, ResourceId item, int count) {
        public Ingredient {
            if (slot < 0) {
                throw new IllegalArgumentException(
                        "ingredient slot must not be negative");
            }
            Objects.requireNonNull(item, "item");
            requirePositive(count, "ingredient count");
        }
    }
}
