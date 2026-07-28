package io.github.greytaiwolf.botplayer.inventory;

import java.util.Objects;
import java.util.UUID;

@FunctionalInterface
public interface InventoryDistanceValidator {
   InventoryDistanceValidator.SpatialStatus validate(UUID var1, UUID var2);

   static InventoryDistanceValidator.SpatialStatus requireResult(InventoryDistanceValidator.SpatialStatus var0) {
      return Objects.requireNonNull(var0, "distance validation result");
   }

   public static enum SpatialStatus {
      IN_RANGE,
      OUT_OF_RANGE,
      DIFFERENT_DIMENSION,
      VIEWER_NOT_ALIVE,
      BOT_NOT_ALIVE;

      public boolean valid() {
         return this == IN_RANGE;
      }
   }
}
