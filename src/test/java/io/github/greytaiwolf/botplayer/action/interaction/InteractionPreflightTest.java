package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InteractionPreflightTest {
   private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
   private static final ResourceId NETHER = new ResourceId("minecraft:the_nether");
   private static final ItemStackFingerprint HELD = ItemStackFingerprint.of(new ResourceId("minecraft:diamond_pickaxe"), 1, 5, "d".repeat(64));
   private static final ItemStackFingerprint CHANGED_HELD = ItemStackFingerprint.of(new ResourceId("minecraft:diamond_pickaxe"), 1, 6, "d".repeat(64));
   private static final InteractionPreflight.Requirements STRICT = new InteractionPreflight.Requirements(OVERWORLD, true, true, Optional.of(HELD));

   @Test
   void matchingServerFactsAllowInvocationButDoNotClaimActionSuccess() {
      InteractionPreflight.Result var1 = InteractionPreflight.evaluate(STRICT, validObservation());
      Assertions.assertTrue(var1.allowed());
      Assertions.assertEquals(InteractionPreflight.Failure.NONE, var1.failure());
      Assertions.assertEquals(ActionFailureCode.NONE, var1.actionFailureCode());
      Assertions.assertEquals("", var1.safeSummary());
   }

   @Test
   void firstFailureIsStableInTheDocumentedGuardOrder() {
      assertFailure(
         InteractionPreflight.Failure.TARGET_UNAVAILABLE,
         observation(OVERWORLD, false, Optional.empty(), false, false, OptionalDouble.empty(), 16.0, false, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.DIMENSION_MISMATCH,
         observation(NETHER, true, Optional.of(NETHER), false, false, OptionalDouble.of(100.0), 16.0, false, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.CHUNK_UNLOADED,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), false, false, OptionalDouble.of(100.0), 16.0, false, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.TARGET_CHANGED,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, false, OptionalDouble.of(100.0), 16.0, false, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.OUT_OF_REACH,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.01), 16.0, false, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.LINE_OF_SIGHT_BLOCKED,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.0), 16.0, false, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.GAME_MODE_DENIED,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.0), 16.0, true, false, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.COOLDOWN_ACTIVE,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.0), 16.0, true, true, false, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.HELD_ITEM_CHANGED,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.0), 16.0, true, true, true, CHANGED_HELD, false)
      );
      assertFailure(
         InteractionPreflight.Failure.WORLD_PERMISSION_DENIED,
         observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.0), 16.0, true, true, true, HELD, false)
      );
   }

   @Test
   void optionalLineOfSightCooldownAndHeldChecksCanBeDisabledExplicitly() {
      InteractionPreflight.Requirements var1 = new InteractionPreflight.Requirements(OVERWORLD, false, false, Optional.empty());
      InteractionPreflight.Observation var2 = observation(
         OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(4.0), 16.0, false, true, false, CHANGED_HELD, true
      );
      Assertions.assertTrue(InteractionPreflight.evaluate(var1, var2).allowed());
   }

   @Test
   void observationsRejectMissingOrNonFiniteFactsForPresentTargets() {
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> observation(OVERWORLD, true, Optional.empty(), true, true, OptionalDouble.empty(), 16.0, true, true, true, HELD, true)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(Double.NaN), 16.0, true, true, true, HELD, true)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(1.0), -1.0, true, true, true, HELD, true)
      );
   }

   @Test
   void deniedResultCannotBeConstructedWithNoFailure() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> InteractionPreflight.Result.denied(InteractionPreflight.Failure.NONE));
      Assertions.assertFalse(InteractionPreflight.Result.denied(InteractionPreflight.Failure.WORLD_PERMISSION_DENIED).allowed());
      Assertions.assertEquals(
         ActionFailureCode.PERMISSION_DENIED, InteractionPreflight.Result.denied(InteractionPreflight.Failure.WORLD_PERMISSION_DENIED).actionFailureCode()
      );
   }

   private static void assertFailure(InteractionPreflight.Failure var0, InteractionPreflight.Observation var1) {
      InteractionPreflight.Result var2 = InteractionPreflight.evaluate(STRICT, var1);
      Assertions.assertFalse(var2.allowed());
      Assertions.assertEquals(var0, var2.failure());
   }

   private static InteractionPreflight.Observation validObservation() {
      return observation(OVERWORLD, true, Optional.of(OVERWORLD), true, true, OptionalDouble.of(16.0), 16.0, true, true, true, HELD, true);
   }

   private static InteractionPreflight.Observation observation(
      ResourceId var0,
      boolean var1,
      Optional<ResourceId> var2,
      boolean var3,
      boolean var4,
      OptionalDouble var5,
      double var6,
      boolean var8,
      boolean var9,
      boolean var10,
      ItemStackFingerprint var11,
      boolean var12
   ) {
      return new InteractionPreflight.Observation(var0, var1, var2, var3, var4, var5, var6, var8, var9, var10, var11, var12);
   }
}
