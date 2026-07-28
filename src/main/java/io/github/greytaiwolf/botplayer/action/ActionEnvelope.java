package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public record ActionEnvelope(
   UUID actionId, UUID botId, long botGeneration, String idempotencyKey, long deadlineTick, int maxTicks, ActionRequest action, ActionOrigin origin
) {
   public static final int MAX_IDEMPOTENCY_KEY_LENGTH = 128;
   public static final int MAX_ACTION_TICKS = 6000;
   private static final UUID ZERO_UUID = new UUID(0L, 0L);
   private static final Pattern IDEMPOTENCY_KEY_PATTERN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}");

   public ActionEnvelope(
      UUID actionId, UUID botId, long botGeneration, String idempotencyKey, long deadlineTick, int maxTicks, ActionRequest action, ActionOrigin origin
   ) {
      requireNonZero(actionId, "actionId");
      requireNonZero(botId, "botId");
      if (botGeneration <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         idempotencyKey = validateIdempotencyKey(idempotencyKey);
         if (deadlineTick < 0L) {
            throw new IllegalArgumentException("deadlineTick must not be negative");
         } else if (maxTicks >= 1 && maxTicks <= 6000) {
            Objects.requireNonNull(action, "action");
            Objects.requireNonNull(origin, "origin");
            Set<ActionChannel> var11 =
               Objects.requireNonNull(action.channels(), "action channels");
            if (var11.isEmpty()) {
               throw new IllegalArgumentException("action must require at least one channel");
            } else {
               for (ActionChannel var13 : var11) {
                  Objects.requireNonNull(var13, "action channel");
               }

               this.actionId = actionId;
               this.botId = botId;
               this.botGeneration = botGeneration;
               this.idempotencyKey = idempotencyKey;
               this.deadlineTick = deadlineTick;
               this.maxTicks = maxTicks;
               this.action = action;
               this.origin = origin;
            }
         } else {
            throw new IllegalArgumentException("maxTicks must be between 1 and 6000");
         }
      }
   }

   public boolean isExpiredAt(long var1) {
      requireNonNegativeTick(var1, "currentTick");
      return var1 >= this.deadlineTick;
   }

   public boolean hasExhaustedTickBudget(long var1, long var3) {
      requireNonNegativeTick(var1, "startedTick");
      requireNonNegativeTick(var3, "currentTick");
      if (var3 < var1) {
         throw new IllegalArgumentException("currentTick must not precede startedTick");
      } else {
         return var3 - var1 >= (long)this.maxTicks;
      }
   }

   boolean hasSameIdempotentOperation(ActionEnvelope var1) {
      Objects.requireNonNull(var1, "other");
      return this.botId.equals(var1.botId)
         && this.botGeneration == var1.botGeneration
         && this.maxTicks == var1.maxTicks
         && this.action.equals(var1.action)
         && this.origin.equals(var1.origin);
   }

   static String validateIdempotencyKey(String var0) {
      Objects.requireNonNull(var0, "idempotencyKey");
      if (!IDEMPOTENCY_KEY_PATTERN.matcher(var0).matches()) {
         throw new IllegalArgumentException("idempotencyKey must be 1-128 safe ASCII characters");
      } else {
         return var0;
      }
   }

   static void requireNonZero(UUID var0, String var1) {
      Objects.requireNonNull(var0, var1);
      if (ZERO_UUID.equals(var0)) {
         throw new IllegalArgumentException(var1 + " must not be the zero UUID");
      }
   }

   private static void requireNonNegativeTick(long var0, String var2) {
      if (var0 < 0L) {
         throw new IllegalArgumentException(var2 + " must not be negative");
      }
   }
}
