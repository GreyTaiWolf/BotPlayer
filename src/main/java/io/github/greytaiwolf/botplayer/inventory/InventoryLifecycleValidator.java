package io.github.greytaiwolf.botplayer.inventory;

import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface InventoryLifecycleValidator {
   InventoryLifecycleValidator.LifecycleStatus validate(UUID var1, long var2);

   static InventoryLifecycleValidator.LifecycleStatus requireResult(InventoryLifecycleValidator.LifecycleStatus var0) {
      return Objects.requireNonNull(var0, "lifecycle validation result");
   }

   public static enum LifecycleStatus {
      ACTIVE,
      UNKNOWN_BOT,
      NOT_ACTIVE,
      STALE_GENERATION,
      INVALID_INSTANCE,
      SERVER_STOPPING;

      public boolean active() {
         return this == ACTIVE;
      }
   }
}
