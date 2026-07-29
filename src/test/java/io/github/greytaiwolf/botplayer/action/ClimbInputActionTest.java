package io.github.greytaiwolf.botplayer.action;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ClimbInputActionTest {
    @Test
    void climbUsesOnlyTheMoveChannelAndPreservesDirection() {
        ClimbInputAction climb =
                new ClimbInputAction(0.5F, -0.25F, true, false, 5);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        ActionKind.CLIMB_INPUT, climb.kind()),
                () -> Assertions.assertEquals(
                        java.util.Set.of(ActionChannel.MOVE),
                        climb.channels()),
                () -> Assertions.assertTrue(climb.inputState().jump()),
                () -> Assertions.assertFalse(climb.inputState().sneak()),
                () -> Assertions.assertTrue(
                        climb.hasVerticalProgress(64.0D, 64.1D)),
                () -> Assertions.assertFalse(
                        climb.hasVerticalProgress(64.0D, 63.9D)));
    }

    @Test
    void climbRejectsAmbiguousDirectionAndUnboundedDuration() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new ClimbInputAction(
                                0.0F, 0.0F, true, true, 2)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new ClimbInputAction(
                                0.0F, 0.0F, false, false, 2)),
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new ClimbInputAction(
                                0.0F, 0.0F, true, false, 41)));
    }
}
