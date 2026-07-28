package io.github.greytaiwolf.botplayer.inventory;

public final class BotInventoryLayout {
   public static final int SLOT_SPACING = 18;
   public static final int BOT_EQUIPMENT_X = 8;
   public static final int BOT_EQUIPMENT_Y = 18;
   public static final int BOT_OFFHAND_X = 80;
   public static final int BOT_MAIN_X = 8;
   public static final int BOT_MAIN_Y = 44;
   public static final int BOT_HOTBAR_X = 8;
   public static final int BOT_HOTBAR_Y = 102;
   public static final int VIEWER_MAIN_X = 8;
   public static final int VIEWER_MAIN_Y = 140;
   public static final int VIEWER_HOTBAR_X = 8;
   public static final int VIEWER_HOTBAR_Y = 198;
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

   private BotInventoryLayout() {
   }

   public static int botInventoryIndex(int var0) {
      if (var0 >= 0 && var0 < 4) {
         return 39 - var0;
      } else if (var0 == 4) {
         return 40;
      } else if (var0 >= 5 && var0 < 32) {
         return 9 + var0 - 5;
      } else if (var0 >= 32 && var0 < 41) {
         return var0 - 32;
      } else {
         throw new IndexOutOfBoundsException("not a bot menu slot: " + var0);
      }
   }

   public static int viewerInventoryIndex(int var0) {
      if (var0 >= 41 && var0 < 68) {
         return 9 + var0 - 41;
      } else if (var0 >= 68 && var0 < 77) {
         return var0 - 68;
      } else {
         throw new IndexOutOfBoundsException("not a viewer menu slot: " + var0);
      }
   }
}
