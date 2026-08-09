package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * P5A production DAG 可以通过原版动作放置的封闭工作站集合。
 *
 * <p>这不是任意 block id 的别名。每个值同时固定要扣除的玩家账本材料、随后可打开的原版
 * menu family，以及放置后必须回读的完整 block-state 形状。新增工作站必须显式扩展此枚举、
 * validator、已审核模板和测试，不能由物品或 tag 自动推导。
 */
public enum WorkstationKind {
    CRAFTING_TABLE(
            ProductionMaterials.CRAFTING_TABLE,
            MenuFamily.CRAFTING_3X3),
    FURNACE(
            ProductionMaterials.FURNACE,
            MenuFamily.FURNACE);

    private static final Set<String> HORIZONTAL_FACINGS = Set.of(
            "north", "south", "west", "east");

    private final ProductionMaterial material;
    private final MenuFamily menuFamily;

    WorkstationKind(
            ProductionMaterial material, MenuFamily menuFamily) {
        this.material = Objects.requireNonNull(material, "material");
        this.menuFamily = Objects.requireNonNull(menuFamily, "menuFamily");
    }

    /**
     * 放置动作必须从玩家账本精确扣除的一件原版 block item。
     */
    public ProductionMaterial material() {
        return material;
    }

    /**
     * 该方块放置成功后可供后续审核 recipe 使用的精确原版菜单族。
     */
    public MenuFamily menuFamily() {
        return menuFamily;
    }

    /**
     * 工作站方块与其 block item 在当前 P5A 白名单中使用相同的原版资源位置。
     */
    public ResourceId blockId() {
        return material.id();
    }

    /**
     * 以原版放置将产生的完整 state 构造预期指纹。工作台没有属性；熔炉必须固定为未点燃并
     * 使用 placement context 派生出的水平朝向。
     */
    public BlockStateFingerprint expectedPlacedState(String horizontalFacing) {
        Objects.requireNonNull(horizontalFacing, "horizontalFacing");
        return switch (this) {
            case CRAFTING_TABLE -> new BlockStateFingerprint(
                    blockId(), Map.of());
            case FURNACE -> {
                if (!HORIZONTAL_FACINGS.contains(horizontalFacing)) {
                    throw new IllegalArgumentException(
                            "furnace placement facing must be horizontal");
                }
                yield new BlockStateFingerprint(blockId(), Map.of(
                        "facing", horizontalFacing,
                        "lit", "false"));
            }
        };
    }

    /**
     * 只接受上面 {@link #expectedPlacedState(String)} 能表达的完整原版状态，不把“同 block
     * id 但属性未知”当作工作站放置成功。
     */
    public boolean matchesExpectedPlacedState(BlockStateFingerprint state) {
        Objects.requireNonNull(state, "state");
        if (!blockId().equals(state.blockId())) {
            return false;
        }
        return switch (this) {
            case CRAFTING_TABLE -> state.properties().isEmpty();
            case FURNACE -> state.properties().size() == 2
                    && HORIZONTAL_FACINGS.contains(
                            state.properties().get("facing"))
                    && "false".equals(state.properties().get("lit"));
        };
    }
}
