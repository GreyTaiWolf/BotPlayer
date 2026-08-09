package io.github.greytaiwolf.botplayer.skill.menu;

/**
 * 受支持原版 menu 中一个槽位的固定职责。
 *
 * <p>这不是运行时类名猜测。Minecraft 适配器必须先验证实际菜单类，再把它映射为
 * {@link MenuFamily}；未知职责绝不能落入这个模型。
 */
public enum MenuSlotRole {
    RESULT,
    CRAFTING_INPUT,
    FURNACE_INPUT,
    FURNACE_FUEL,
    CONTAINER,
    PLAYER_MAIN,
    PLAYER_HOTBAR,
    ARMOR_HEAD,
    ARMOR_CHEST,
    ARMOR_LEGS,
    ARMOR_FEET,
    OFFHAND
}
