package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;

public enum ActionPriority {
   BACKGROUND(0),
   AUTONOMOUS(100),
   OWNER_TASK(200),
   OWNER_CONTROL(300),
   SURVIVAL(400),
   LIFECYCLE_CLEANUP(500),
   EMERGENCY(600);

   private final int rank;

   private ActionPriority(int nullxx) {
      this.rank = nullxx;
   }

   public int rank() {
      return this.rank;
   }

   public boolean outranks(ActionPriority var1) {
      Objects.requireNonNull(var1, "other");
      return this.rank > var1.rank;
   }
}
