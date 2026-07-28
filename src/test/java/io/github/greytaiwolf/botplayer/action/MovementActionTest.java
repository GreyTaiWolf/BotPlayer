package io.github.greytaiwolf.botplayer.action;

import io.github.greytaiwolf.botplayer.action.input.PlayerInputState;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MovementActionTest {
   @Test
   void moveInputExposesOnlyTheMoveChannelAndImmutableIntent() {
      MoveInputAction var1 = new MoveInputAction(1.0F, -0.5F, true, false, false, 20, 8);
      Assertions.assertEquals(ActionKind.MOVE_INPUT, var1.kind());
      Assertions.assertEquals(Set.of(ActionChannel.MOVE), var1.channels());
      Assertions.assertEquals(new PlayerInputState(1.0F, -0.5F, false, true, false, false), var1.inputState());
      Assertions.assertEquals(25.0, var1.expectedMaximumHorizontalDistance());
   }

   @Test
   void moveInputRejectsUnboundedOrContradictoryRequests() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new MoveInputAction(Float.NaN, 0.0F, false, false, false, 10, 5));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new MoveInputAction(0.0F, 0.0F, false, false, false, 10, 5));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new MoveInputAction(1.0F, 0.0F, true, true, false, 10, 5));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new MoveInputAction(1.0F, 0.0F, false, false, false, 5, 6));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new MoveInputAction(1.0F, 0.0F, false, false, false, 201, 5));
   }

   @Test
   void jumpUsesOrdinaryMoveInputWithoutAPositionTarget() {
      JumpAction var1 = new JumpAction(0.75F, 0.0F, true, 3);
      Assertions.assertEquals(ActionKind.JUMP, var1.kind());
      Assertions.assertEquals(Set.of(ActionChannel.MOVE), var1.channels());
      Assertions.assertEquals(new PlayerInputState(0.75F, 0.0F, true, true, false, false), var1.inputState());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new JumpAction(0.0F, 0.0F, true, 1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new JumpAction(0.0F, 0.0F, false, 0));
   }

   @Test
   void jumpStartClassificationRejectsUnrelatedAirborneMotion() {
      Assertions.assertEquals(
         JumpAction.StartMode.GROUND,
         JumpAction.classifyStart(true, false));
      Assertions.assertEquals(
         JumpAction.StartMode.WATER,
         JumpAction.classifyStart(true, true));
      Assertions.assertEquals(
         JumpAction.StartMode.REJECTED,
         JumpAction.classifyStart(false, false));
   }

   @Test
   void jumpSuccessRequiresCausalPhysicalEvidence() {
      Assertions.assertTrue(
         JumpAction.hasPhysicalSuccess(
            JumpAction.StartMode.GROUND,
            true,
            JumpAction.MIN_GROUND_RISE_BLOCKS,
            10.0D));
      Assertions.assertFalse(
         JumpAction.hasPhysicalSuccess(
            JumpAction.StartMode.GROUND,
            false,
            10.0D,
            10.0D));
      Assertions.assertFalse(
         JumpAction.hasPhysicalSuccess(
            JumpAction.StartMode.GROUND,
            true,
            Math.nextDown(JumpAction.MIN_GROUND_RISE_BLOCKS),
            10.0D));
      Assertions.assertTrue(
         JumpAction.hasPhysicalSuccess(
            JumpAction.StartMode.WATER,
            false,
            0.0D,
            JumpAction.MIN_WATER_DISPLACEMENT_BLOCKS));
      Assertions.assertFalse(
         JumpAction.hasPhysicalSuccess(
            JumpAction.StartMode.REJECTED,
            true,
            10.0D,
            10.0D));
   }
}
