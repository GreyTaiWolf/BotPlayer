package io.github.greytaiwolf.botplayer.action.input;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import java.util.Objects;
import java.util.UUID;

public record PlayerInputOwner(UUID botId, long botGeneration, UUID actionId) {
   private static final UUID ZERO_UUID = new UUID(0L, 0L);

   public PlayerInputOwner(UUID botId, long botGeneration, UUID actionId) {
      requireNonZero(botId, "botId");
      if (botGeneration <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         requireNonZero(actionId, "actionId");
         this.botId = botId;
         this.botGeneration = botGeneration;
         this.actionId = actionId;
      }
   }

   public static PlayerInputOwner from(ActionEnvelope var0) {
      Objects.requireNonNull(var0, "envelope");
      return new PlayerInputOwner(var0.botId(), var0.botGeneration(), var0.actionId());
   }

   private static void requireNonZero(UUID var0, String var1) {
      Objects.requireNonNull(var0, var1);
      if (ZERO_UUID.equals(var0)) {
         throw new IllegalArgumentException(var1 + " must not be the zero UUID");
      }
   }
}
