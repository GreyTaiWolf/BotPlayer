package io.github.greytaiwolf.botplayer.inventory;

public final class BotInventoryLayout {
    public static final int IMAGE_WIDTH = 176;
    public static final int IMAGE_HEIGHT = 256;
    public static final int SLOT_SPACING = 18;
    public static final int BOT_EQUIPMENT_X = 8;
    public static final int BOT_EQUIPMENT_Y = 8;
    public static final int BOT_OFFHAND_X = 77;
    public static final int BOT_OFFHAND_Y = 62;
    public static final int BOT_MAIN_X = 8;
    public static final int BOT_MAIN_Y = 84;
    public static final int BOT_HOTBAR_X = 8;
    public static final int BOT_HOTBAR_Y = 142;
    public static final int VIEWER_MAIN_X = 8;
    public static final int VIEWER_MAIN_Y = 174;
    public static final int VIEWER_HOTBAR_X = 8;
    public static final int VIEWER_HOTBAR_Y = 232;
    public static final int BOT_ARMOR_START = 0;
    public static final int BOT_ARMOR_END = 4;
    public static final int BOT_OFFHAND_SLOT = 4;
    public static final int BOT_MAIN_START = 5;
    public static final int BOT_MAIN_END = 32;
    public static final int BOT_HOTBAR_START = 32;
    public static final int BOT_HOTBAR_END = 41;
    public static final int VIEWER_MAIN_START = 41;
    public static final int VIEWER_MAIN_END = 68;
    public static final int VIEWER_HOTBAR_START = 68;
    public static final int VIEWER_HOTBAR_END = 77;
    public static final int TOTAL_MENU_SLOTS = 77;
    public static final int BOT_INVENTORY_SIZE = 41;
    public static final int VIEWER_INVENTORY_SIZE = 36;

    private BotInventoryLayout() {}

    public static int botInventoryIndex(int menuIndex) {
        if (menuIndex >= 0 && menuIndex < 4) {
            return 39 - menuIndex;
        } else if (menuIndex == 4) {
            return 40;
        } else if (menuIndex >= 5 && menuIndex < 32) {
            return 9 + menuIndex - 5;
        } else if (menuIndex >= 32 && menuIndex < 41) {
            return menuIndex - 32;
        } else {
            throw new IndexOutOfBoundsException(
                    "not a bot menu slot: " + menuIndex);
        }
    }

    public static int viewerInventoryIndex(int menuIndex) {
        if (menuIndex >= 41 && menuIndex < 68) {
            return 9 + menuIndex - 41;
        } else if (menuIndex >= 68 && menuIndex < 77) {
            return menuIndex - 68;
        } else {
            throw new IndexOutOfBoundsException(
                    "not a viewer menu slot: " + menuIndex);
        }
    }
}
