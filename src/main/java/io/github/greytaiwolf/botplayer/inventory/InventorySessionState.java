package io.github.greytaiwolf.botplayer.inventory;

import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

public enum InventorySessionState {
   OPENING,
   OPEN,
   CLOSING,
   CLOSED;

   private static final Set<InventorySessionState> FROM_OPENING = EnumSet.of(OPEN, CLOSING);
   private static final Set<InventorySessionState> FROM_OPEN = EnumSet.of(CLOSING);
   private static final Set<InventorySessionState> FROM_CLOSING = EnumSet.of(CLOSED);

   public boolean canTransitionTo(InventorySessionState var1) {
      Objects.requireNonNull(var1, "next");

      return switch (this) {
         case OPENING -> FROM_OPENING.contains(var1);
         case OPEN -> FROM_OPEN.contains(var1);
         case CLOSING -> FROM_CLOSING.contains(var1);
         case CLOSED -> false;
      };
   }

   public void requireTransitionTo(InventorySessionState var1) {
      if (!this.canTransitionTo(var1)) {
         throw new IllegalStateException("Illegal inventory session transition: " + this + " -> " + var1);
      }
   }

   public boolean isTerminal() {
      return this == CLOSED;
   }
}
