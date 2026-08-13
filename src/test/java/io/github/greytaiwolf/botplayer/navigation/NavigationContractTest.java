package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ControllerKind;
import io.github.greytaiwolf.botplayer.navigation.path.TraversalKind;
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
    void sprintCanBeDisabledWithoutChangingSafeTraversalBounds() {
        NavigationPolicy safe = NavigationPolicy.safeDefault();
        NavigationPolicy controlled = safe.withSprint(false);

        Assertions.assertAll(
                () -> Assertions.assertTrue(safe.allowSprint()),
                () -> Assertions.assertFalse(controlled.allowSprint()),
                () -> Assertions.assertEquals(safe.allowSwim(),
                        controlled.allowSwim()),
                () -> Assertions.assertEquals(safe.allowClimb(),
                        controlled.allowClimb()),
                () -> Assertions.assertEquals(safe.maximumReplans(),
                        controlled.maximumReplans()),
                () -> Assertions.assertEquals(safe.maximumRecoveryAttempts(),
                        controlled.maximumRecoveryAttempts()));
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

    @Test
    void groundedArrivalIsOptInAndPreventsMidJumpCompletion() {
        NavigationGoal goal = new NavigationGoal.ExactPosition(
                "minecraft:overworld", new GridPoint(3, 65, 3), 0, 0);
        NavigationRequest legacy = new NavigationRequest(
                new UUID(4L, 1L),
                new UUID(5L, 1L),
                1L,
                goal,
                NavigationPolicy.safeDefault(),
                100L,
                100,
                "navigation-arrival-legacy");
        NavigationRequest grounded = new NavigationRequest(
                new UUID(4L, 2L),
                new UUID(5L, 1L),
                1L,
                goal,
                NavigationArrivalRequirement.GROUNDED_GRID_CELL,
                NavigationPolicy.safeDefault(),
                100L,
                100,
                "navigation-arrival-grounded");
        GridPoint target = new GridPoint(3, 65, 3);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        NavigationArrivalRequirement.GRID_CELL,
                        legacy.arrivalRequirement()),
                () -> Assertions.assertTrue(NavigationService.arrivalSatisfied(
                        legacy, target, false)),
                () -> Assertions.assertFalse(NavigationService.arrivalSatisfied(
                        grounded, target, false)),
                () -> Assertions.assertTrue(NavigationService.arrivalSatisfied(
                        grounded, target, true)),
                () -> Assertions.assertTrue(
                        NavigationService.requiresGroundedArrivalReplan(
                                grounded, true, false)),
                () -> Assertions.assertFalse(
                        NavigationService.requiresGroundedArrivalReplan(
                                grounded, true, true)),
                () -> Assertions.assertFalse(
                        NavigationService.requiresGroundedArrivalReplan(
                                legacy, true, false)));
    }

    @Test
    void safeDropWaypointWaitsForTheDestinationBlockLayer() {
        GridPoint dropTarget = new GridPoint(3, 1, 3);
        GridPoint stillFalling = new GridPoint(3, 2, 3);

        Assertions.assertAll(
                () -> Assertions.assertFalse(
                        NavigationService.waypointVerticalPositionReached(
                                TraversalKind.DROP_SAFE,
                                stillFalling,
                                dropTarget,
                                2.0D),
                        "a final safe-drop waypoint must not be consumed one layer above its target"),
                () -> Assertions.assertTrue(
                        NavigationService.waypointVerticalPositionReached(
                                TraversalKind.DROP_SAFE,
                                dropTarget,
                                dropTarget,
                                1.0D),
                        "the exact destination grid can be consumed before an arrival policy checks onGround"),
                () -> Assertions.assertTrue(
                        NavigationService.waypointVerticalPositionReached(
                                TraversalKind.WALK_CARDINAL,
                                stillFalling,
                                dropTarget,
                                2.0D),
                        "ordinary legacy walking waypoints retain their existing vertical tolerance"));
    }

    @Test
    void unstablePlanningStartUsesExistingRetryBudgets() {
        NavigationPolicy policy = new NavigationPolicy(
                true,
                true,
                true,
                true,
                false,
                false,
                0,
                false,
                0,
                3,
                5,
                6.0F,
                2,
                1);

        Assertions.assertAll(
                () -> Assertions.assertFalse(
                        NavigationService
                                .unstablePlanningStartRetryBudgetExhausted(
                                        0, 0, policy)),
                () -> Assertions.assertFalse(
                        NavigationService
                                .unstablePlanningStartRetryBudgetExhausted(
                                        2, 1, policy)),
                () -> Assertions.assertTrue(
                        NavigationService
                                .unstablePlanningStartRetryBudgetExhausted(
                                        3, 1, policy)),
                () -> Assertions.assertTrue(
                        NavigationService
                                .unstablePlanningStartRetryBudgetExhausted(
                                        2, 2, policy)));
    }
}
