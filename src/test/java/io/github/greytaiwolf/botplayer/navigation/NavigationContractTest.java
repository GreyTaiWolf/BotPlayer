package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class NavigationContractTest {
    @Test
    void safePolicyCannotHideTerrainMutationBudget() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new NavigationPolicy(
                                true,
                                true,
                                true,
                                true,
                                false,
                                false,
                                1,
                                false,
                                0,
                                3,
                                5,
                                6.0F,
                                16,
                                5)),
                () -> Assertions.assertFalse(
                        NavigationPolicy.safeDefault().allowBreak()),
                () -> Assertions.assertFalse(
                        NavigationPolicy.safeDefault().allowPlace()));
    }

    @Test
    void controllerOriginIsDistinctFromPlanAndSkill() {
        UUID runId =
                UUID.fromString("00000000-0000-0000-0000-000000000403");
        ActionOrigin origin = ActionOrigin.fromController(
                ControllerKind.NAVIGATION, runId);

        Assertions.assertAll(
                () -> Assertions.assertTrue(origin.planId().isEmpty()),
                () -> Assertions.assertTrue(origin.skillRunId().isEmpty()),
                () -> Assertions.assertEquals(
                        runId,
                        origin.controller()
                                .orElseThrow()
                                .controllerRunId()),
                () -> Assertions.assertFalse(origin.isUntracked()));
    }

    @Test
    void exactAndRadiusGoalsUseBoundedTolerance() {
        NavigationGoal exact = new NavigationGoal.ExactPosition(
                "minecraft:overworld",
                new GridPoint(10, 64, 10),
                1,
                0);
        NavigationGoal near = new NavigationGoal.NearPosition(
                "minecraft:overworld",
                new GridPoint(10, 64, 10),
                2);

        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        exact.reached(new GridPoint(11, 64, 10))),
                () -> Assertions.assertFalse(
                        exact.reached(new GridPoint(11, 65, 10))),
                () -> Assertions.assertTrue(
                        near.reached(new GridPoint(11, 65, 10))),
                () -> Assertions.assertFalse(
                        near.reached(new GridPoint(13, 64, 10))));
    }
}
