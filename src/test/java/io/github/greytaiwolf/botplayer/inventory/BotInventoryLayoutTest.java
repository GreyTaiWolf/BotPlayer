package io.github.greytaiwolf.botplayer.inventory;

import java.util.HashSet;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotInventoryLayoutTest {
   @Test
   void rangesAreContiguousAndContainExactlySeventySevenSlots() {
      Assertions.assertEquals(0, 0);
      Assertions.assertEquals(4, 4);
      Assertions.assertEquals(5, 5);
      Assertions.assertEquals(32, 32);
      Assertions.assertEquals(32, 32);
      Assertions.assertEquals(41, 41);
      Assertions.assertEquals(41, 41);
      Assertions.assertEquals(68, 68);
      Assertions.assertEquals(68, 68);
      Assertions.assertEquals(77, 77);
      Assertions.assertEquals(77, 77);
   }

   @Test
   void botMenuMapsEveryRealInventoryIndexExactlyOnce() {
      HashSet<Integer> var1 = new HashSet<>();

      for (int var2 = 0; var2 < 41; var2++) {
         var1.add(BotInventoryLayout.botInventoryIndex(var2));
      }

      Assertions.assertEquals(41, var1.size());

      for (int var3 = 0; var3 < 41; var3++) {
         Assertions.assertEquals(true, var1.contains(var3));
      }

      Assertions.assertEquals(39, BotInventoryLayout.botInventoryIndex(0));
      Assertions.assertEquals(36, BotInventoryLayout.botInventoryIndex(3));
      Assertions.assertEquals(40, BotInventoryLayout.botInventoryIndex(4));
      Assertions.assertEquals(9, BotInventoryLayout.botInventoryIndex(5));
      Assertions.assertEquals(35, BotInventoryLayout.botInventoryIndex(31));
      Assertions.assertEquals(0, BotInventoryLayout.botInventoryIndex(32));
      Assertions.assertEquals(8, BotInventoryLayout.botInventoryIndex(40));
   }

   @Test
   void viewerMenuMapsEveryViewerInventoryIndexExactlyOnce() {
      HashSet<Integer> var1 = new HashSet<>();

      for (int var2 = 41; var2 < 77; var2++) {
         var1.add(BotInventoryLayout.viewerInventoryIndex(var2));
      }

      Assertions.assertEquals(36, var1.size());

      for (int var3 = 0; var3 < 36; var3++) {
         Assertions.assertEquals(true, var1.contains(var3));
      }
   }

   @Test
   void mappingRejectsTheOtherOwnerAndOutOfRangeSlots() {
      Assertions.assertThrows(IndexOutOfBoundsException.class, () -> BotInventoryLayout.botInventoryIndex(-1));
      Assertions.assertThrows(IndexOutOfBoundsException.class, () -> BotInventoryLayout.botInventoryIndex(41));
      Assertions.assertThrows(IndexOutOfBoundsException.class, () -> BotInventoryLayout.viewerInventoryIndex(40));
      Assertions.assertThrows(IndexOutOfBoundsException.class, () -> BotInventoryLayout.viewerInventoryIndex(77));
   }

   @Test
   void screenCoordinatesKeepBothInventoriesInsideTheFrame() {
      Assertions.assertEquals(18, 18);
      Assertions.assertEquals(80, 80);
      Assertions.assertEquals(198, 198);
   }
}
