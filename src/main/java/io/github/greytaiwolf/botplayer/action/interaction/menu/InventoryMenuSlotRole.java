package io.github.greytaiwolf.botplayer.action.interaction.menu;

/**
 * 原版玩家 {@code InventoryMenu} 中槽位承担的稳定角色。
 *
 * <p>合成输出和 2×2 合成输入只用于前置状态校验，P5A 事务不会点击它们。
 */
public enum InventoryMenuSlotRole {
    CRAFT_READ_ONLY(false),
    ARMOR(true),
    MAIN(true),
    HOTBAR(true),
    OFFHAND(true);

    private final boolean inventoryBacked;

    InventoryMenuSlotRole(boolean inventoryBacked) {
        this.inventoryBacked = inventoryBacked;
    }

    public boolean inventoryBacked() {
        return inventoryBacked;
    }
}
