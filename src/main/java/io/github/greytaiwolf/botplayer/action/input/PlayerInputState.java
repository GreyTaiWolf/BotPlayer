package io.github.greytaiwolf.botplayer.action.input;

public record PlayerInputState(float forward, float strafe, boolean jump, boolean sprint, boolean sneak, boolean swim) {
   public static final PlayerInputState IDLE = new PlayerInputState(0.0F, 0.0F, false, false, false, false);

   public PlayerInputState(float forward, float strafe, boolean jump, boolean sprint, boolean sneak, boolean swim) {
      requireAxis(forward, "forward");
      requireAxis(strafe, "strafe");
      if (!sneak || !sprint && !swim) {
         if (sprint && forward <= 0.0F) {
            throw new IllegalArgumentException("sprint requires positive forward input");
         } else if (swim && forward <= 0.0F) {
            throw new IllegalArgumentException("swim requires positive forward input");
         } else {
            this.forward = forward;
            this.strafe = strafe;
            this.jump = jump;
            this.sprint = sprint;
            this.sneak = sneak;
            this.swim = swim;
         }
      } else {
         throw new IllegalArgumentException("sneak cannot be combined with sprint or swim");
      }
   }

   public boolean isIdle() {
      return this.equals(IDLE);
   }

   private static void requireAxis(float var0, String var1) {
      if (!Float.isFinite(var0) || var0 < -1.0F || var0 > 1.0F) {
         throw new IllegalArgumentException(var1 + " must be finite and between -1 and 1");
      }
   }
}
