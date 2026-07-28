package io.github.greytaiwolf.botplayer.inventory;

import java.util.Objects;

public final class BotInventoryLock {
   private final InventorySessionToken token;
   private boolean held = true;

   BotInventoryLock(InventorySessionToken var1) {
      this.token = Objects.requireNonNull(var1, "token");
   }

   public InventorySessionToken token() {
      return this.token;
   }

   public boolean held() {
      return this.held;
   }

   public boolean heldBy(InventorySessionToken var1) {
      return this.held && this.token.equals(var1);
   }

   boolean release(InventorySessionToken var1) {
      Objects.requireNonNull(var1, "candidate");
      if (!this.heldBy(var1)) {
         return false;
      } else {
         this.held = false;
         return true;
      }
   }
}
