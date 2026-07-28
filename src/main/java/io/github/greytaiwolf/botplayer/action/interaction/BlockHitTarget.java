package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.Objects;

public record BlockHitTarget(BlockTargetFingerprint target, BlockHitTarget.Face face, double localX, double localY, double localZ, boolean inside) {
   public BlockHitTarget(BlockTargetFingerprint target, BlockHitTarget.Face face, double localX, double localY, double localZ, boolean inside) {
      Objects.requireNonNull(target, "target");
      Objects.requireNonNull(face, "face");
      localX = requireLocalCoordinate(localX, "localX");
      localY = requireLocalCoordinate(localY, "localY");
      localZ = requireLocalCoordinate(localZ, "localZ");
      this.target = target;
      this.face = face;
      this.localX = localX;
      this.localY = localY;
      this.localZ = localZ;
      this.inside = inside;
   }

   public double worldX() {
      return (double)this.target.position().x() + this.localX;
   }

   public double worldY() {
      return (double)this.target.position().y() + this.localY;
   }

   public double worldZ() {
      return (double)this.target.position().z() + this.localZ;
   }

   private static double requireLocalCoordinate(double var0, String var2) {
      InteractionChecks.requireFinite(var0, var2);
      if (!(var0 < 0.0) && !(var0 > 1.0)) {
         return var0;
      } else {
         throw new IllegalArgumentException(var2 + " must be between 0 and 1");
      }
   }

   public static enum Face {
      DOWN,
      UP,
      NORTH,
      SOUTH,
      WEST,
      EAST;
   }
}
