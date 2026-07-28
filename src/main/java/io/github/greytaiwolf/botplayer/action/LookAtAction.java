package io.github.greytaiwolf.botplayer.action;

import java.util.Set;

public record LookAtAction(double x, double y, double z) implements ActionRequest {
   private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.LOOK);

   public LookAtAction(double x, double y, double z) {
      requireFinite(x, "x");
      requireFinite(y, "y");
      requireFinite(z, "z");
      this.x = x;
      this.y = y;
      this.z = z;
   }

   @Override
   public ActionKind kind() {
      return ActionKind.LOOK_AT;
   }

   @Override
   public Set<ActionChannel> channels() {
      return CHANNELS;
   }

   private static void requireFinite(double var0, String var2) {
      if (!Double.isFinite(var0)) {
         throw new IllegalArgumentException(var2 + " must be finite");
      }
   }
}
