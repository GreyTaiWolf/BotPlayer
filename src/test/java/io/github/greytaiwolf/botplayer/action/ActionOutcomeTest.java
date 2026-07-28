package io.github.greytaiwolf.botplayer.action;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionOutcomeTest {
   private static final UUID ACTION_ID = new UUID(0L, 1L);

   @Test
   void acceptsCoherentTerminalOutcomes() {
      Assertions.assertDoesNotThrow(() -> outcome(ActionState.SUCCEEDED, ActionFailureCode.NONE, 10L, 11L, "looked"));
      Assertions.assertDoesNotThrow(() -> outcome(ActionState.FAILED, ActionFailureCode.DEADLINE_EXCEEDED, 10L, 12L, "deadline exceeded"));
      Assertions.assertDoesNotThrow(() -> outcome(ActionState.CANCELLED, ActionFailureCode.CANCELLED, 10L, 10L, ""));
      Assertions.assertDoesNotThrow(() -> outcome(ActionState.PREEMPTED, ActionFailureCode.PREEMPTED, 10L, 10L, ""));
      Assertions.assertDoesNotThrow(() -> outcome(ActionState.STALE, ActionFailureCode.STALE_GENERATION, 10L, 10L, ""));
   }

   @Test
   void rejectsNonTerminalOrIncoherentResults() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.RUNNING, ActionFailureCode.NONE, 10L, 10L, ""));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.SUCCEEDED, ActionFailureCode.INTERNAL_ERROR, 10L, 10L, ""));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.FAILED, ActionFailureCode.NONE, 10L, 10L, ""));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.CANCELLED, ActionFailureCode.PREEMPTED, 10L, 10L, ""));
   }

   @Test
   void boundsTicksAndSafeSummary() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.SUCCEEDED, ActionFailureCode.NONE, -1L, 1L, ""));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.SUCCEEDED, ActionFailureCode.NONE, 2L, 1L, ""));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L, 1L, " has whitespace"));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L, 1L, "line\nbreak"));
      Assertions.assertThrows(IllegalArgumentException.class, () -> outcome(ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L, 1L, "x".repeat(257)));
   }

   @Test
   void boundsAndDefensivelyCopiesEvidence() {
      ArrayList<ActionEvidence> var1 = new ArrayList<>();
      var1.add(new ActionEvidence("look.yaw", "90.0"));
      ActionOutcome var2 = new ActionOutcome(ACTION_ID, ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L, 1L, var1, "verified");
      var1.clear();
      Assertions.assertEquals(1, var2.evidence().size());
      Assertions.assertThrows(UnsupportedOperationException.class, () -> var2.evidence().clear());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ActionEvidence("Invalid Key", "value"));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ActionEvidence("value", "x".repeat(161)));
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new ActionOutcome(
               ACTION_ID, ActionState.SUCCEEDED, ActionFailureCode.NONE, 1L, 1L, Collections.nCopies(17, new ActionEvidence("item", "value")), "too much"
            )
      );
   }

   private static ActionOutcome outcome(ActionState var0, ActionFailureCode var1, long var2, long var4, String var6) {
      return new ActionOutcome(ACTION_ID, var0, var1, var2, var4, List.of(), var6);
   }
}
