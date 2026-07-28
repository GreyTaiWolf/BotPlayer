package io.github.greytaiwolf.botplayer.action;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

public enum ActionChannel {
   MOVE,
   LOOK,
   MAIN_HAND,
   OFF_HAND,
   INVENTORY,
   INTERACT,
   CHAT;

   private static final Set<ActionChannel> ALL = Collections.unmodifiableSet(EnumSet.allOf(ActionChannel.class));

   public static Set<ActionChannel> all() {
      return ALL;
   }
}
