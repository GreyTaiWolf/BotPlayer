package io.github.greytaiwolf.botplayer.navigation.path;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BoundedAStarPlannerTest {
    private static final UUID BOT_ID =
            UUID.fromString("00000000-0000-0000-0000-000000000401");
    private static final String DIMENSION = "minecraft:overworld";
    private static final TraversalCell FLOOR =
            new TraversalCell(true, true, true, false, false, false, false);
    private static final TraversalCell BLOCKED =
            new TraversalCell(true, false, false, false, false, false, false);
    private static final TraversalCell CLEAR_AIR =
            new TraversalCell(true, true, false, false, false, false, false);

    @Test
    void straightRouteIsCompleteAndDeterministic() {
        NavigationSnapshot snapshot = snapshot(7, 1, 3, FLOOR);
        GridPoint start = new GridPoint(0, 0, 1);
        NavigationGoal goal = goal(6, 0, 1);
        BoundedAStarPlanner planner = new BoundedAStarPlanner(500);

        RoutePlan first = planner.plan(
                snapshot,
                start,
                goal,
                NavigationPolicy.safeDefault(),
                () -> false);
        RoutePlan second = planner.plan(
                snapshot,
                start,
                goal,
                NavigationPolicy.safeDefault(),
                () -> false);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        RoutePlanStatus.COMPLETE, first.status()),
                () -> Assertions.assertEquals(first, second),
                () -> Assertions.assertEquals(start, first.nodes().getFirst().point()),
                () -> Assertions.assertEquals(
                        goal.center(), first.nodes().getLast().point()),
                () -> Assertions.assertTrue(first.totalCost() >= 6_000L));
    }

    @Test
    void diagonalCannotCutAClosedCorner() {
        TraversalCell[] cells = filled(3, 1, 3, FLOOR);
        cells[index(1, 0, 0, 3, 3)] = BLOCKED;
        cells[index(0, 0, 1, 3, 3)] = BLOCKED;
        NavigationSnapshot snapshot = snapshot(3, 1, 3, cells, true);

        RoutePlan plan = new BoundedAStarPlanner(100).plan(
                snapshot,
                new GridPoint(0, 0, 0),
                goal(1, 0, 1),
                NavigationPolicy.safeDefault(),
                () -> false);

        Assertions.assertEquals(RoutePlanStatus.NO_PATH, plan.status());
    }

    @Test
    void raisedDiagonalTargetRoutesThroughCardinalJump() {
        TraversalCell[] cells = filled(3, 2, 3, FLOOR);
        cells[index(1, 0, 1, 3, 3)] = BLOCKED;
        cells[index(0, 1, 0, 3, 3)] = CLEAR_AIR;
        cells[index(1, 1, 0, 3, 3)] = CLEAR_AIR;
        cells[index(1, 1, 1, 3, 3)] = FLOOR;
        NavigationSnapshot snapshot = snapshot(3, 2, 3, cells, true);

        RoutePlan plan = new BoundedAStarPlanner(100).plan(
                snapshot,
                new GridPoint(0, 0, 0),
                goal(1, 1, 1),
                NavigationPolicy.safeDefault(),
                () -> false);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        RoutePlanStatus.COMPLETE, plan.status()),
                () -> Assertions.assertEquals(
                        new GridPoint(1, 1, 1),
                        plan.nodes().getLast().point()),
                () -> Assertions.assertTrue(plan.nodes().stream()
                        .anyMatch(node -> node.traversalKind()
                                == TraversalKind.JUMP_UP_ONE)),
                () -> Assertions.assertTrue(plan.nodes().stream()
                        .filter(node -> node.traversalKind()
                                == TraversalKind.JUMP_UP_ONE)
                        .allMatch(node -> {
                            int index = plan.nodes().indexOf(node);
                            GridPoint previous = plan.nodes()
                                    .get(index - 1)
                                    .point();
                            return node.point().x() == previous.x()
                                    || node.point().z() == previous.z();
                        }));
    }

    @Test
    void unknownBoundaryCanReturnAProgressingPartialFrontier() {
        TraversalCell[] cells = filled(5, 1, 1, FLOOR);
        cells[index(4, 0, 0, 5, 1)] = TraversalCell.UNKNOWN;
        NavigationSnapshot snapshot = snapshot(5, 1, 1, cells, false);

        RoutePlan plan = new BoundedAStarPlanner(100).plan(
                snapshot,
                new GridPoint(0, 0, 0),
                goal(20, 0, 0),
                NavigationPolicy.safeDefault(),
                () -> false);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        RoutePlanStatus.PARTIAL_FRONTIER, plan.status()),
                () -> Assertions.assertEquals(
                        new GridPoint(3, 0, 0),
                        plan.nodes().getLast().point()),
                () -> Assertions.assertTrue(plan.touchedUnknownBoundary()));
    }

    @Test
    void expansionAndCancellationAreBoundedTerminalResults() {
        NavigationSnapshot snapshot = snapshot(20, 1, 20, FLOOR);
        NavigationGoal goal = goal(19, 0, 19);

        RoutePlan exhausted = new BoundedAStarPlanner(1).plan(
                snapshot,
                new GridPoint(0, 0, 0),
                goal,
                NavigationPolicy.safeDefault(),
                () -> false);
        RoutePlan cancelled = new BoundedAStarPlanner(500).plan(
                snapshot,
                new GridPoint(0, 0, 0),
                goal,
                NavigationPolicy.safeDefault(),
                () -> true);

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        RoutePlanStatus.BUDGET_EXHAUSTED,
                        exhausted.status()),
                () -> Assertions.assertEquals(1, exhausted.expandedNodes()),
                () -> Assertions.assertEquals(
                        RoutePlanStatus.CANCELLED, cancelled.status()),
                () -> Assertions.assertEquals(0, cancelled.expandedNodes()));
    }

    @Test
    void snapshotDefensivelyCopiesCells() {
        TraversalCell[] cells = filled(2, 1, 1, FLOOR);
        NavigationSnapshot snapshot = snapshot(2, 1, 1, cells, true);
        cells[0] = BLOCKED;
        TraversalCell[] copy = snapshot.copyCells();
        copy[0] = BLOCKED;

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        FLOOR, snapshot.cell(new GridPoint(0, 0, 0))),
                () -> Assertions.assertNotSame(cells, snapshot.copyCells()));
    }

    @Test
    void movementCostOverflowSaturates() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        Long.MAX_VALUE,
                        MovementCostModel.saturatedAdd(
                                Long.MAX_VALUE - 1L, 2L)),
                () -> Assertions.assertEquals(
                        Long.MAX_VALUE,
                        MovementCostModel.saturatedMultiply(
                                Long.MAX_VALUE, 2)));
    }

    private static NavigationGoal goal(int x, int y, int z) {
        return new NavigationGoal.ExactPosition(
                DIMENSION, new GridPoint(x, y, z), 0, 0);
    }

    private static NavigationSnapshot snapshot(
            int sizeX, int sizeY, int sizeZ, TraversalCell fill) {
        return snapshot(
                sizeX,
                sizeY,
                sizeZ,
                filled(sizeX, sizeY, sizeZ, fill),
                true);
    }

    private static NavigationSnapshot snapshot(
            int sizeX,
            int sizeY,
            int sizeZ,
            TraversalCell[] cells,
            boolean complete) {
        return new NavigationSnapshot(
                UUID.fromString("00000000-0000-0000-0000-000000000402"),
                BOT_ID,
                1L,
                DIMENSION,
                10L,
                new GridPoint(0, 0, 0),
                sizeX,
                sizeY,
                sizeZ,
                cells,
                complete,
                0L);
    }

    private static TraversalCell[] filled(
            int sizeX, int sizeY, int sizeZ, TraversalCell fill) {
        TraversalCell[] cells = new TraversalCell[sizeX * sizeY * sizeZ];
        java.util.Arrays.fill(cells, fill);
        return cells;
    }

    private static int index(
            int x, int y, int z, int sizeX, int sizeZ) {
        return (y * sizeZ + z) * sizeX + x;
    }
}
