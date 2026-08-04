package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.OptionalInt;

/**
 * 原版玩家背包 41 个库存索引与 {@code InventoryMenu} 槽位的纯 Java 映射。
 *
 * <p>库存索引为热栏 0..8、主背包 9..35、盔甲 36..39 和副手 40；
 * menu 槽 0..4 是只读合成角色，5..45 才映射到这 41 个库存槽。
 */
public final class PlayerInventoryMenuLayout {
    public static final int INVENTORY_SLOT_COUNT = 41;
    public static final int FIRST_MENU_SLOT = 0;
    public static final int LAST_MENU_SLOT = 45;
    public static final int FIRST_MAIN_INVENTORY_SLOT = 9;
    public static final int LAST_MAIN_INVENTORY_SLOT = 35;
    public static final int FIRST_ARMOR_INVENTORY_SLOT = 36;
    public static final int LAST_ARMOR_INVENTORY_SLOT = 39;
    public static final int OFFHAND_INVENTORY_SLOT = 40;
    public static final int FIRST_HOTBAR_INVENTORY_SLOT = 0;
    public static final int LAST_HOTBAR_INVENTORY_SLOT = 8;

    private PlayerInventoryMenuLayout() {
    }

    public static InventoryMenuSlotRole roleOfMenuSlot(int menuSlot) {
        requireMenuSlot(menuSlot);
        if (menuSlot <= 4) {
            return InventoryMenuSlotRole.CRAFT_READ_ONLY;
        }
        if (menuSlot <= 8) {
            return InventoryMenuSlotRole.ARMOR;
        }
        if (menuSlot <= 35) {
            return InventoryMenuSlotRole.MAIN;
        }
        if (menuSlot <= 44) {
            return InventoryMenuSlotRole.HOTBAR;
        }
        return InventoryMenuSlotRole.OFFHAND;
    }

    public static int menuSlotForInventorySlot(int inventorySlot) {
        requireInventorySlot(inventorySlot);
        if (isHotbarInventorySlot(inventorySlot)) {
            return 36 + inventorySlot;
        }
        if (isMainInventorySlot(inventorySlot)) {
            return inventorySlot;
        }
        if (isArmorInventorySlot(inventorySlot)) {
            return 44 - inventorySlot;
        }
        return 45;
    }

    public static OptionalInt inventorySlotForMenuSlot(int menuSlot) {
        InventoryMenuSlotRole role = roleOfMenuSlot(menuSlot);
        return switch (role) {
            case CRAFT_READ_ONLY -> OptionalInt.empty();
            case ARMOR -> OptionalInt.of(44 - menuSlot);
            case MAIN -> OptionalInt.of(menuSlot);
            case HOTBAR -> OptionalInt.of(menuSlot - 36);
            case OFFHAND -> OptionalInt.of(OFFHAND_INVENTORY_SLOT);
        };
    }

    public static boolean isHotbarInventorySlot(int inventorySlot) {
        return inventorySlot >= FIRST_HOTBAR_INVENTORY_SLOT
                && inventorySlot <= LAST_HOTBAR_INVENTORY_SLOT;
    }

    public static boolean isMainInventorySlot(int inventorySlot) {
        return inventorySlot >= FIRST_MAIN_INVENTORY_SLOT
                && inventorySlot <= LAST_MAIN_INVENTORY_SLOT;
    }

    public static boolean isArmorInventorySlot(int inventorySlot) {
        return inventorySlot >= FIRST_ARMOR_INVENTORY_SLOT
                && inventorySlot <= LAST_ARMOR_INVENTORY_SLOT;
    }

    public static boolean isEquipmentInventorySlot(int inventorySlot) {
        return isArmorInventorySlot(inventorySlot)
                || inventorySlot == OFFHAND_INVENTORY_SLOT;
    }

    public static void requireInventorySlot(int inventorySlot) {
        if (inventorySlot < 0 || inventorySlot >= INVENTORY_SLOT_COUNT) {
            throw new IllegalArgumentException(
                    "inventory slot must be in 0..40");
        }
    }

    public static void requireMenuSlot(int menuSlot) {
        if (menuSlot < FIRST_MENU_SLOT || menuSlot > LAST_MENU_SLOT) {
            throw new IllegalArgumentException(
                    "menu slot must be in 0..45");
        }
    }
}
