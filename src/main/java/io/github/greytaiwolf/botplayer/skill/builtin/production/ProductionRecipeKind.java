package io.github.greytaiwolf.botplayer.skill.builtin.production;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;

/**
 * 生产配方必须走的原版菜单族；不允许按槽位数模糊猜测。
 */
public enum ProductionRecipeKind {
    INVENTORY_CRAFTING(MenuFamily.INVENTORY_2X2),
    WORKBENCH_CRAFTING(MenuFamily.CRAFTING_3X3),
    FURNACE_SMELTING(MenuFamily.FURNACE);

    private final MenuFamily menuFamily;

    ProductionRecipeKind(MenuFamily menuFamily) {
        this.menuFamily = menuFamily;
    }

    public MenuFamily menuFamily() {
        return menuFamily;
    }
}
