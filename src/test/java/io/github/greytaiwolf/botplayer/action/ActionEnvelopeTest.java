package io.github.greytaiwolf.botplayer.action;

import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionEnvelopeTest {
   private static final UUID ACTION_ID = new UUID(0L, 1L);
   private static final UUID BOT_ID = new UUID(0L, 2L);

   @Test
   void acceptsStrictBoundaryValuesAndUsesExclusiveDeadline() {
      String var1 = "k".repeat(128);
      ActionEnvelope var2 = new ActionEnvelope(ACTION_ID, BOT_ID, 1L, var1, 100L, 6000, new LookAtAction(1.0, -64.0, 3.5), ActionOrigin.none());
      Assertions.assertEquals(ActionKind.LOOK_AT, var2.action().kind());
      Assertions.assertFalse(var2.isExpiredAt(99L));
      Assertions.assertTrue(var2.isExpiredAt(100L));
      Assertions.assertFalse(var2.hasExhaustedTickBudget(10L, 10L));
      Assertions.assertTrue(var2.hasExhaustedTickBudget(10L, 6010L));
   }

   @Test
   void rejectsInvalidIdentityGenerationKeyAndBudgets() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(new UUID(0L, 0L), BOT_ID, 1L, "valid", 10L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, new UUID(0L, 0L), 1L, "valid", 10L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 0L, "valid", 10L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 1L, "", 10L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 1L, "has space", 10L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 1L, "k".repeat(129), 10L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 1L, "valid", -1L, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 1L, "valid", 10L, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> envelope(ACTION_ID, BOT_ID, 1L, "valid", 10L, 6001));
   }

   @Test
   void rejectsNonFiniteLookTargetsAndInvalidTickQueries() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new LookAtAction(Double.NaN, 0.0, 0.0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new LookAtAction(0.0, Double.POSITIVE_INFINITY, 0.0));
      ActionEnvelope var1 = envelope(ACTION_ID, BOT_ID, 1L, "valid", 10L, 2);
      Assertions.assertThrows(IllegalArgumentException.class, () -> var1.isExpiredAt(-1L));
      Assertions.assertThrows(IllegalArgumentException.class, () -> var1.hasExhaustedTickBudget(3L, 2L));
   }

   @Test
   void originUsesAbsenceInsteadOfZeroIdentifiers() {
      Assertions.assertTrue(ActionOrigin.none().isUntracked());
      UUID var1 = new UUID(0L, 3L);
      UUID var2 = new UUID(0L, 4L);
      ActionOrigin var3 = ActionOrigin.fromPlanAndSkillRun(var1, var2);
      Assertions.assertEquals(Optional.of(var1), var3.planId());
      Assertions.assertEquals(Optional.of(var2), var3.skillRunId());
      Assertions.assertThrows(IllegalArgumentException.class, () -> ActionOrigin.fromPlan(new UUID(0L, 0L)));
      Assertions.assertThrows(NullPointerException.class, () -> new ActionOrigin(null, Optional.empty()));
   }

   @Test
   void techniqueChildOriginRequiresItsExactSkillAndCannotClaimAController() {
      UUID var1 = new UUID(0L, 5L);
      TechniqueChildOrigin var2 = new TechniqueChildOrigin(
         new UUID(0L, 6L), new UUID(0L, 7L), 1L
      );
      ActionOrigin var3 = ActionOrigin.fromTechniqueChild(var1, var2);
      Assertions.assertEquals(Optional.of(var1), var3.skillRunId());
      Assertions.assertEquals(Optional.of(var2), var3.techniqueChild());
      Assertions.assertFalse(var3.isUntracked());
      Assertions.assertThrows(IllegalArgumentException.class,
         () -> new ActionOrigin(Optional.empty(), Optional.empty(),
            Optional.empty(), Optional.of(var2)));
      Assertions.assertThrows(IllegalArgumentException.class,
         () -> new ActionOrigin(Optional.empty(), Optional.of(var1),
            Optional.of(new ControllerOrigin(ControllerKind.SAFETY,
               new UUID(0L, 8L))), Optional.of(var2)));
   }

   @Test
   void stopOwnsEveryChannelThroughAnImmutableSet() {
      StopAction var1 = new StopAction();
      Assertions.assertEquals(ActionKind.STOP, var1.kind());
      Assertions.assertEquals(ActionChannel.all(), var1.channels());
      Assertions.assertThrows(UnsupportedOperationException.class, () -> var1.channels().remove(ActionChannel.MOVE));
   }

   private static ActionEnvelope envelope(UUID var0, UUID var1, long var2, String var4, long var5, int var7) {
      return new ActionEnvelope(var0, var1, var2, var4, var5, var7, new LookAtAction(1.0, 2.0, 3.0), ActionOrigin.none());
   }
}
