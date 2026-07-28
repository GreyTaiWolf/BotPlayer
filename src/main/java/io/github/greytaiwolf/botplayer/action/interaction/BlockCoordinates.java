package io.github.greytaiwolf.botplayer.action.interaction;

public record BlockCoordinates(int x, int y, int z) {
   public static final int MAX_HORIZONTAL_COORDINATE = 30000000;

   public BlockCoordinates(int x, int y, int z) {
      if (Math.abs((long)x) <= 30000000L && Math.abs((long)z) <= 30000000L) {
         this.x = x;
         this.y = y;
         this.z = z;
      } else {
         throw new IllegalArgumentException("horizontal block coordinate exceeds 30000000");
      }
   }

   public int chunkX() {
      return this.x >> 4;
   }

   public int chunkZ() {
      return this.z >> 4;
   }
}
