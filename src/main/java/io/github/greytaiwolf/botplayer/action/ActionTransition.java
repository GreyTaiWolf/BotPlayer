package io.github.greytaiwolf.botplayer.action;

import java.util.Objects;
import java.util.UUID;

public record ActionTransition(UUID botId, long botGeneration, UUID actionId, ActionKind kind, ActionState from, ActionState to, long serverTick) {
   public ActionTransition(UUID botId, long botGeneration, UUID actionId, ActionKind kind, ActionState from, ActionState to, long serverTick) {
      ActionEnvelope.requireNonZero(botId, "botId");
      if (botGeneration <= 0L) {
         throw new IllegalArgumentException("botGeneration must be positive");
      } else {
         ActionEnvelope.requireNonZero(actionId, "actionId");
         Objects.requireNonNull(kind, "kind");
         Objects.requireNonNull(from, "from");
         Objects.requireNonNull(to, "to");
         if (serverTick < 0L) {
            throw new IllegalArgumentException("serverTick must not be negative");
         } else if (!from.canTransitionTo(to)) {
            throw new IllegalArgumentException("Action transition record must describe a legal transition");
         } else {
            this.botId = botId;
            this.botGeneration = botGeneration;
            this.actionId = actionId;
            this.kind = kind;
            this.from = from;
            this.to = to;
            this.serverTick = serverTick;
         }
      }
   }
}
