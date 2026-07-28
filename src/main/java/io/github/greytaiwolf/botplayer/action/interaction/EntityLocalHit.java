package io.github.greytaiwolf.botplayer.action.interaction;

public record EntityLocalHit(double x, double y, double z) {
   public static final double MAX_ABSOLUTE_OFFSET = 1024.0;

   public EntityLocalHit(double x, double y, double z) {
      x = requireBounded(x, "x");
      y = requireBounded(y, "y");
      z = requireBounded(z, "z");
      this.x = x;
      this.y = y;
      this.z = z;
   }

   private static double requireBounded(double var0, String var2) {
      InteractionChecks.requireFinite(var0, var2);
      if (Math.abs(var0) > 1024.0) {
         throw new IllegalArgumentException(var2 + " exceeds 1024.0");
      } else {
         return var0;
      }
   }
}
