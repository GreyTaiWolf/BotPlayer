package io.github.greytaiwolf.botplayer.action;

import java.util.Set;

public record StopAction() implements ActionRequest {
   @Override
   public ActionKind kind() {
      return ActionKind.STOP;
   }

   @Override
   public Set<ActionChannel> channels() {
      return ActionChannel.all();
   }
}
