package io.github.greytaiwolf.botplayer.navigation.path;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.NavigationGoal;
import io.github.greytaiwolf.botplayer.navigation.NavigationPolicy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

public final class BoundedAStarPlanner implements RoutePlanner {
    public static final int MAXIMUM_EXPANSIONS = 250_000;
    private static final long INFINITE = Long.MAX_VALUE;
    private static final int[][] HORIZONTAL_DIRECTIONS = {
        {1, 0}, {0, 1}, {-1, 0}, {0, -1},
        {1, 1}, {-1, 1}, {-1, -1}, {1, -1}
    };

    private final int maximumExpansions;
    private final MovementCostModel costModel;

    public BoundedAStarPlanner(int maximumExpansions) {
        if (maximumExpansions < 1
                || maximumExpansions > MAXIMUM_EXPANSIONS) {
            throw new IllegalArgumentException(
                    "maximumExpansions must be between 1 and "
                            + MAXIMUM_EXPANSIONS);
        }
        this.maximumExpansions = maximumExpansions;
        this.costModel = new MovementCostModel();
    }

    @Override
    public RoutePlan plan(
            NavigationSnapshot snapshot,
            GridPoint start,
            NavigationGoal goal,
            NavigationPolicy policy,
            BooleanSupplier cancelled) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(goal, "goal");
        Objects.requireNonNull(policy, "policy");
        Objects.requireNonNull(cancelled, "cancelled");
        if (!snapshot.dimension().equals(goal.dimension())
                || !snapshot.contains(start)
                || !snapshot.cell(start).traversable()) {
            return empty(snapshot, RoutePlanStatus.INVALID_SNAPSHOT, 0, false);
        }

        int count = snapshot.cellCount();
        long[] costs = new long[count];
        Arrays.fill(costs, INFINITE);
        int[] parents = new int[count];
        Arrays.fill(parents, -1);
        TraversalKind[] traversals = new TraversalKind[count];
        boolean[] closed = new boolean[count];
        PriorityQueue<Candidate> open = new PriorityQueue<>(
                Comparator.comparingLong(Candidate::f)
                        .thenComparingLong(Candidate::h)
                        .thenComparingLong(Candidate::g)
                        .thenComparingInt(Candidate::index));

        int startIndex = snapshot.index(start);
        long startHeuristic = heuristic(start, goal);
        costs[startIndex] = 0L;
        traversals[startIndex] = TraversalKind.START;
        open.add(new Candidate(
                startIndex, 0L, startHeuristic, startHeuristic));
        int bestIndex = startIndex;
        long bestHeuristic = startHeuristic;
        int expanded = 0;
        boolean touchedUnknown = false;

        while (!open.isEmpty()) {
            if (cancelled.getAsBoolean()) {
                return empty(
                        snapshot,
                        RoutePlanStatus.CANCELLED,
                        expanded,
                        touchedUnknown);
            }
            Candidate candidate = open.remove();
            if (closed[candidate.index()]
                    || costs[candidate.index()] != candidate.g()) {
                continue;
            }
            closed[candidate.index()] = true;
            expanded++;
            GridPoint current = snapshot.point(candidate.index());
            if (goal.reached(current)) {
                return route(
                        snapshot,
                        RoutePlanStatus.COMPLETE,
                        candidate.index(),
                        costs,
                        parents,
                        traversals,
                        expanded,
                        touchedUnknown);
            }
            if (candidate.h() < bestHeuristic
                    || (candidate.h() == bestHeuristic
                            && candidate.index() < bestIndex)) {
                bestHeuristic = candidate.h();
                bestIndex = candidate.index();
            }
            if (expanded >= maximumExpansions) {
                return empty(
                        snapshot,
                        RoutePlanStatus.BUDGET_EXHAUSTED,
                        expanded,
                        touchedUnknown);
            }

            for (int[] direction : HORIZONTAL_DIRECTIONS) {
                int dx = direction[0];
                int dz = direction[1];
                GridPoint same = new GridPoint(
                        current.x() + dx, current.y(), current.z() + dz);
                touchedUnknown |= !snapshot.contains(same)
                        || !snapshot.cell(same).known();
                if (canMoveHorizontal(snapshot, current, same, dx, dz)) {
                    TraversalCell targetCell = snapshot.cell(same);
                    TraversalKind kind = horizontalKind(
                            targetCell, dx != 0 && dz != 0);
                    relax(
                            snapshot,
                            goal,
                            candidate.index(),
                            same,
                            kind,
                            0,
                            costs,
                            parents,
                            traversals,
                            closed,
                            open);
                    continue;
                }

                GridPoint up = new GridPoint(
                        same.x(), same.y() + 1, same.z());
                // The action backend only has a reliable physical contract for a
                // cardinal one-block jump.  A diagonal raised waypoint can be
                // consumed while the player is still airborne, so route through
                // cardinal ground cells instead of claiming that shortcut.
                if ((dx == 0 || dz == 0)
                        && snapshot.contains(up)
                        && snapshot.cell(up).traversable()
                        && hasJumpClearance(snapshot, current, same, up)) {
                    relax(
                            snapshot,
                            goal,
                            candidate.index(),
                            up,
                            TraversalKind.JUMP_UP_ONE,
                            1,
                            costs,
                            parents,
                            traversals,
                            closed,
                            open);
                }
                for (int drop = 1; drop <= policy.maximumSafeDrop(); drop++) {
                    GridPoint down = new GridPoint(
                            same.x(), same.y() - drop, same.z());
                    if (!snapshot.contains(down)) {
                        touchedUnknown = true;
                        break;
                    }
                    TraversalCell downCell = snapshot.cell(down);
                    if (downCell.traversable() && downCell.stableSupport()) {
                        relax(
                                snapshot,
                                goal,
                                candidate.index(),
                                down,
                                TraversalKind.DROP_SAFE,
                                drop,
                                costs,
                                parents,
                                traversals,
                                closed,
                                open);
                        break;
                    }
                }
            }

            TraversalCell currentCell = snapshot.cell(current);
            if (policy.allowSwim() && currentCell.water()) {
                touchedUnknown |= relaxVertical(
                        snapshot,
                        goal,
                        candidate.index(),
                        current,
                        1,
                        TraversalKind.SWIM_UP,
                        costs,
                        parents,
                        traversals,
                        closed,
                        open);
                touchedUnknown |= relaxVertical(
                        snapshot,
                        goal,
                        candidate.index(),
                        current,
                        -1,
                        TraversalKind.SWIM_DOWN,
                        costs,
                        parents,
                        traversals,
                        closed,
                        open);
            }
            if (policy.allowClimb() && currentCell.climbable()) {
                touchedUnknown |= relaxVertical(
                        snapshot,
                        goal,
                        candidate.index(),
                        current,
                        1,
                        TraversalKind.CLIMB_UP,
                        costs,
                        parents,
                        traversals,
                        closed,
                        open);
                touchedUnknown |= relaxVertical(
                        snapshot,
                        goal,
                        candidate.index(),
                        current,
                        -1,
                        TraversalKind.CLIMB_DOWN,
                        costs,
                        parents,
                        traversals,
                        closed,
                        open);
            }
        }

        if (bestIndex != startIndex
                && bestHeuristic < startHeuristic
                && snapshot.cell(snapshot.point(bestIndex)).traversable()
                && (touchedUnknown
                        || !snapshot.contains(goal.center()))) {
            return route(
                    snapshot,
                    RoutePlanStatus.PARTIAL_FRONTIER,
                    bestIndex,
                    costs,
                    parents,
                    traversals,
                    expanded,
                    touchedUnknown);
        }
        return empty(
                snapshot,
                RoutePlanStatus.NO_PATH,
                expanded,
                touchedUnknown);
    }

    private void relax(
            NavigationSnapshot snapshot,
            NavigationGoal goal,
            int currentIndex,
            GridPoint target,
            TraversalKind traversal,
            int verticalBlocks,
            long[] costs,
            int[] parents,
            TraversalKind[] traversals,
            boolean[] closed,
            PriorityQueue<Candidate> open) {
        int targetIndex = snapshot.index(target);
        if (closed[targetIndex]) {
            return;
        }
        long nextCost = MovementCostModel.saturatedAdd(
                costs[currentIndex],
                costModel.edgeCost(traversal, verticalBlocks));
        if (nextCost >= costs[targetIndex]) {
            return;
        }
        costs[targetIndex] = nextCost;
        parents[targetIndex] = currentIndex;
        traversals[targetIndex] = traversal;
        long h = heuristic(target, goal);
        open.add(new Candidate(
                targetIndex,
                nextCost,
                h,
                MovementCostModel.saturatedAdd(nextCost, h)));
    }

    private boolean relaxVertical(
            NavigationSnapshot snapshot,
            NavigationGoal goal,
            int currentIndex,
            GridPoint current,
            int dy,
            TraversalKind traversal,
            long[] costs,
            int[] parents,
            TraversalKind[] traversals,
            boolean[] closed,
            PriorityQueue<Candidate> open) {
        GridPoint target =
                new GridPoint(current.x(), current.y() + dy, current.z());
        if (!snapshot.contains(target)) {
            return true;
        }
        TraversalCell targetCell = snapshot.cell(target);
        if (targetCell.traversable()
                && (targetCell.water() || targetCell.climbable())) {
            relax(
                    snapshot,
                    goal,
                    currentIndex,
                    target,
                    traversal,
                    1,
                    costs,
                    parents,
                    traversals,
                    closed,
                    open);
        }
        return !targetCell.known();
    }

    private static boolean canMoveHorizontal(
            NavigationSnapshot snapshot,
            GridPoint current,
            GridPoint target,
            int dx,
            int dz) {
        TraversalCell targetCell = snapshot.cell(target);
        if (!targetCell.traversable()) {
            return false;
        }
        if (dx != 0 && dz != 0) {
            GridPoint sideX = new GridPoint(
                    current.x() + dx, current.y(), current.z());
            GridPoint sideZ = new GridPoint(
                    current.x(), current.y(), current.z() + dz);
            if (!snapshot.cell(sideX).traversable()
                    || !snapshot.cell(sideZ).traversable()) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasJumpClearance(
            NavigationSnapshot snapshot,
            GridPoint current,
            GridPoint blockedTarget,
            GridPoint targetAbove) {
        GridPoint aboveCurrent =
                new GridPoint(current.x(), current.y() + 1, current.z());
        return snapshot.cell(aboveCurrent).bodyClear()
                && !snapshot.cell(aboveCurrent).hazardous()
                && snapshot.cell(blockedTarget).known()
                && snapshot.cell(targetAbove).traversable();
    }

    private static TraversalKind horizontalKind(
            TraversalCell cell, boolean diagonal) {
        if (cell.openableDoor()) {
            return TraversalKind.OPEN_DOOR;
        }
        if (cell.water()) {
            return TraversalKind.SWIM_HORIZONTAL;
        }
        return diagonal
                ? TraversalKind.WALK_DIAGONAL
                : TraversalKind.WALK_CARDINAL;
    }

    private static long heuristic(
            GridPoint point, NavigationGoal goal) {
        GridPoint center = goal.center();
        long dx = Math.max(
                0L,
                Math.abs((long) point.x() - center.x())
                        - goal.horizontalTolerance());
        long dz = Math.max(
                0L,
                Math.abs((long) point.z() - center.z())
                        - goal.horizontalTolerance());
        long dy = Math.max(
                0L,
                Math.abs((long) point.y() - center.y())
                        - goal.verticalTolerance());
        long diagonal = Math.min(dx, dz);
        long straight = Math.max(dx, dz) - diagonal;
        long horizontal = MovementCostModel.saturatedAdd(
                MovementCostModel.saturatedMultiply(
                        MovementCostModel.DIAGONAL,
                        Math.toIntExact(Math.min(diagonal, Integer.MAX_VALUE))),
                MovementCostModel.saturatedMultiply(
                        MovementCostModel.CARDINAL,
                        Math.toIntExact(Math.min(straight, Integer.MAX_VALUE))));
        return MovementCostModel.saturatedAdd(
                horizontal,
                MovementCostModel.saturatedMultiply(
                        MovementCostModel.CARDINAL,
                        Math.toIntExact(Math.min(dy, Integer.MAX_VALUE))));
    }

    private static RoutePlan route(
            NavigationSnapshot snapshot,
            RoutePlanStatus status,
            int goalIndex,
            long[] costs,
            int[] parents,
            TraversalKind[] traversals,
            int expanded,
            boolean touchedUnknown) {
        List<RouteNode> reversed = new ArrayList<>();
        int cursor = goalIndex;
        while (cursor >= 0) {
            GridPoint point = snapshot.point(cursor);
            TraversalCell cell = snapshot.cell(point);
            reversed.add(new RouteNode(
                    point,
                    cell.locomotionMode(),
                    Objects.requireNonNull(
                            traversals[cursor], "traversal")));
            cursor = parents[cursor];
        }
        List<RouteNode> nodes = new ArrayList<>(reversed.size());
        for (int index = reversed.size() - 1; index >= 0; index--) {
            nodes.add(reversed.get(index));
        }
        return new RoutePlan(
                snapshot.snapshotId(),
                status,
                nodes,
                costs[goalIndex],
                expanded,
                touchedUnknown);
    }

    private static RoutePlan empty(
            NavigationSnapshot snapshot,
            RoutePlanStatus status,
            int expanded,
            boolean touchedUnknown) {
        return new RoutePlan(
                snapshot.snapshotId(),
                status,
                List.of(),
                0L,
                expanded,
                touchedUnknown);
    }

    private record Candidate(int index, long g, long h, long f) {}
}
