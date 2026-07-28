package io.github.greytaiwolf.botplayer.action;

import java.util.Set;

public record WaitAction(int ticks) implements ActionRequest {
   private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MOVE);

   public WaitAction(int ticks) {
      if (ticks >= 1 && ticks <= 6000) {
         this.ticks = ticks;
      } else {
         throw new IllegalArgumentException("ticks must be between 1 and 6000");
      }
   }

   @Override
   public ActionKind kind() {
      return ActionKind.WAIT;
   }

   @Override
   public Set<ActionChannel> channels() {
      return CHANNELS;
   }
}
