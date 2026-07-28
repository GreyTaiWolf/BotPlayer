package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;
import java.util.UUID;

final class InteractionChecks {
   private static final UUID ZERO_UUID = new UUID(0L, 0L);

   private InteractionChecks() {
   }

   static UUID requireNonZeroUuid(UUID var0, String var1) {
      Objects.requireNonNull(var0, var1);
      if (ZERO_UUID.equals(var0)) {
         throw new IllegalArgumentException(var1 + " must not be the zero UUID");
      } else {
         return var0;
      }
   }

   static double requireFinite(double var0, String var2) {
      if (!Double.isFinite(var0)) {
         throw new IllegalArgumentException(var2 + " must be finite");
      } else {
         return var0;
      }
   }

   static double requireFiniteNonNegative(double var0, String var2) {
      requireFinite(var0, var2);
      if (var0 < 0.0) {
         throw new IllegalArgumentException(var2 + " must not be negative");
      } else {
         return var0;
      }
   }
}
