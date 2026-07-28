package io.github.greytaiwolf.botplayer.inventory;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventorySessionTokenTest {
   private static final UUID BOT_ID = new UUID(0L, 1L);
   private static final UUID VIEWER_ID = new UUID(0L, 2L);
   private static final UUID NONCE = new UUID(0L, 3L);
   private static final UUID ZERO_UUID = new UUID(0L, 0L);

   @Test
   void preservesExactAuthorityTuple() {
      InventorySessionToken var1 = new InventorySessionToken(BOT_ID, 7L, VIEWER_ID, NONCE);
      Assertions.assertEquals(BOT_ID, var1.botId());
      Assertions.assertEquals(7L, var1.botGeneration());
      Assertions.assertEquals(VIEWER_ID, var1.viewerId());
      Assertions.assertEquals(NONCE, var1.nonce());
   }

   @Test
   void rejectsNullZeroAndNonPositiveFields() {
      Assertions.assertThrows(NullPointerException.class, () -> new InventorySessionToken(null, 1L, VIEWER_ID, NONCE));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new InventorySessionToken(ZERO_UUID, 1L, VIEWER_ID, NONCE));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new InventorySessionToken(BOT_ID, 0L, VIEWER_ID, NONCE));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new InventorySessionToken(BOT_ID, 1L, ZERO_UUID, NONCE));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new InventorySessionToken(BOT_ID, 1L, VIEWER_ID, ZERO_UUID));
   }
}
