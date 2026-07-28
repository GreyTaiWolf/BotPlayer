package io.github.greytaiwolf.botplayer.inventory;

import java.util.Objects;
import java.util.UUID;

public record InventorySessionToken(UUID botId, long botGeneration, UUID viewerId, UUID nonce) {
   private static final UUID ZERO_UUID = new UUID(0L, 0L);

   public InventorySessionToken(UUID botId, long botGeneration, UUID viewerId, UUID nonce) {
      requireNonZero(botId, "botId");
      requireNonZero(viewerId, "viewerId");
      requireNonZero(nonce, "nonce");
      if (botGeneration <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         this.botId = botId;
         this.botGeneration = botGeneration;
         this.viewerId = viewerId;
         this.nonce = nonce;
      }
   }

   private static void requireNonZero(UUID var0, String var1) {
      Objects.requireNonNull(var0, var1);
      if (ZERO_UUID.equals(var0)) {
         throw new IllegalArgumentException(var1 + " must not be zero");
      }
   }
}
