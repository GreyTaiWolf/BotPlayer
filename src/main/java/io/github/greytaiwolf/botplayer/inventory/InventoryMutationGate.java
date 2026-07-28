package io.github.greytaiwolf.botplayer.inventory;

import java.util.UUID;

@FunctionalInterface
public interface InventoryMutationGate {
   InventoryMutationGate.MutationStatus check(UUID var1, long var2);

   default boolean mayMutate(UUID var1, long var2) {
      return this.check(var1, var2) == InventoryMutationGate.MutationStatus.ALLOWED;
   }

   public static enum MutationStatus {
      ALLOWED,
      VIEWER_WRITE_LOCKED,
      STALE_VIEWER_LOCK,
      UNKNOWN_BOT,
      BOT_NOT_ACTIVE,
      STALE_GENERATION,
      INVALID_INSTANCE,
      SERVER_STOPPING;
   }
}
