package io.github.greytaiwolf.botplayer.action;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionStateTest {
   private static final Map<ActionState, Set<ActionState>> LEGAL_TRANSITIONS = Map.of(
      ActionState.QUEUED,
      EnumSet.of(ActionState.VALIDATING, ActionState.CANCELLED, ActionState.PREEMPTED),
      ActionState.VALIDATING,
      EnumSet.of(ActionState.RUNNING, ActionState.FAILED, ActionState.CANCELLED, ActionState.PREEMPTED, ActionState.STALE),
      ActionState.RUNNING,
      EnumSet.of(ActionState.VERIFYING, ActionState.FAILED, ActionState.CANCELLED, ActionState.PREEMPTED, ActionState.STALE),
      ActionState.VERIFYING,
      EnumSet.of(ActionState.SUCCEEDED, ActionState.FAILED, ActionState.CANCELLED, ActionState.PREEMPTED, ActionState.STALE)
   );

   @Test
   void centralTableAcceptsOnlyDocumentedTransitions() {
      for (ActionState var4 : ActionState.values()) {
         Set<ActionState> var5 = LEGAL_TRANSITIONS.getOrDefault(var4, Set.of());

         for (ActionState var9 : ActionState.values()) {
            Assertions.assertTrue(var4.canTransitionTo(var9) == var5.contains(var9), () -> "Unexpected transition decision for " + var4 + " -> " + var9);
         }
      }
   }

   @Test
   void requireTransitionRejectsSkippedStagesAndTerminalRevival() {
      Assertions.assertDoesNotThrow(() -> ActionState.QUEUED.requireTransitionTo(ActionState.VALIDATING));
      Assertions.assertThrows(IllegalStateException.class, () -> ActionState.QUEUED.requireTransitionTo(ActionState.RUNNING));
      Assertions.assertThrows(IllegalStateException.class, () -> ActionState.RUNNING.requireTransitionTo(ActionState.SUCCEEDED));

      for (ActionState var2 : EnumSet.of(ActionState.SUCCEEDED, ActionState.FAILED, ActionState.CANCELLED, ActionState.PREEMPTED, ActionState.STALE)) {
         Assertions.assertTrue(var2.isTerminal());
         Assertions.assertFalse(var2.canTransitionTo(ActionState.QUEUED));
      }
   }

   @Test
   void nullTransitionIsRejected() {
      Assertions.assertThrows(NullPointerException.class, () -> ActionState.QUEUED.canTransitionTo(null));
   }
}
