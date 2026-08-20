package io.github.greytaiwolf.botplayer.building.construction;

import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;

/**
 * Exact-cover, bounded work-package graph for one immutable blueprint revision.
 *
 * <p>This contract validates a partition and dependencies; it is not a construction scheduler or
 * placement plan. In particular, a topological order only serializes data dependencies. It does
 * not prove support, reachability, safety, protection permission, material availability, human
 * override handling, or that any block may be placed in Minecraft.
 */
public record ConstructionWorkPlan(
        Blueprint blueprint,
        List<ConstructionWorkPackage> workPackages) {
    public static final int MIN_CELLS_PER_LARGE_PACKAGE = 16;
    public static final int MAX_CELLS_PER_PACKAGE = ConstructionWorkPackage.MAX_CELLS;
    public static final int MAX_WORK_PACKAGES = ConstructionWorkPackageKey.MAX_WORK_PACKAGES;

    private static final Comparator<ConstructionWorkPackage> PACKAGE_ORDER =
            Comparator.comparingInt(workPackage -> workPackage.key().ordinal());

    public ConstructionWorkPlan {
        Objects.requireNonNull(blueprint, "blueprint");
        workPackages = canonicalAndValidate(blueprint, workPackages);
    }

    /**
     * Deterministically partitions canonical blueprint cells into a conservative linear DAG.
     *
     * <p>The partition is intentionally content-only. Its linear dependencies establish bounded
     * checkpoint units without claiming that canonical coordinate order is a valid physical build
     * order. A future site-aware compiler must replace or revalidate this graph before execution.
     */
    public static ConstructionWorkPlan partition(Blueprint blueprint) {
        Blueprint required = Objects.requireNonNull(blueprint, "blueprint");
        List<BlueprintCell> cells = required.cells();
        int packageCount = cells.size() <= MAX_CELLS_PER_PACKAGE
                ? 1
                : (cells.size() + MAX_CELLS_PER_PACKAGE - 1) / MAX_CELLS_PER_PACKAGE;
        List<ConstructionWorkPackage> packages = new ArrayList<>(packageCount);
        int baseSize = cells.size() / packageCount;
        int largerPackageCount = cells.size() % packageCount;
        int cursor = 0;
        ConstructionWorkPackageKey previous = null;
        for (int ordinal = 0; ordinal < packageCount; ordinal++) {
            int packageSize = baseSize + (ordinal < largerPackageCount ? 1 : 0);
            ConstructionWorkPackageKey key = ConstructionWorkPackageKey.forBlueprint(required,
                    ordinal);
            List<ConstructionWorkPackageKey> prerequisites = previous == null
                    ? List.of()
                    : List.of(previous);
            packages.add(new ConstructionWorkPackage(key,
                    cells.subList(cursor, cursor + packageSize), prerequisites));
            cursor += packageSize;
            previous = key;
        }
        return new ConstructionWorkPlan(required, packages);
    }

    /**
     * Returns a stable topological package order, breaking independent-package ties by ordinal.
     */
    public List<ConstructionWorkPackage> topologicallyOrderedPackages() {
        Map<ConstructionWorkPackageKey, ConstructionWorkPackage> byKey = indexByKey(workPackages);
        List<ConstructionWorkPackageKey> order = topologicalKeys(workPackages, byKey);
        return order.stream().map(byKey::get).toList();
    }

    private static List<ConstructionWorkPackage> canonicalAndValidate(Blueprint blueprint,
            List<ConstructionWorkPackage> supplied) {
        Objects.requireNonNull(supplied, "workPackages");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException("construction work packages must not be empty");
        }
        List<ConstructionWorkPackage> canonical = new ArrayList<>(
                Math.min(supplied.size(), MAX_WORK_PACKAGES));
        for (ConstructionWorkPackage workPackage : supplied) {
            canonical.add(Objects.requireNonNull(workPackage, "construction work package"));
            if (canonical.size() > MAX_WORK_PACKAGES) {
                throw new IllegalArgumentException(
                        "construction work packages exceed the bounded limit");
            }
        }
        canonical.sort(PACKAGE_ORDER);
        for (int ordinal = 0; ordinal < canonical.size(); ordinal++) {
            ConstructionWorkPackage workPackage = canonical.get(ordinal);
            ConstructionWorkPackageKey expected = ConstructionWorkPackageKey.forBlueprint(blueprint,
                    ordinal);
            if (!expected.equals(workPackage.key())) {
                throw new IllegalArgumentException(
                        "work package key must bind the exact blueprint and contiguous ordinal");
            }
        }
        if (blueprint.cells().size() <= MAX_CELLS_PER_PACKAGE && canonical.size() != 1) {
            throw new IllegalArgumentException(
                    "a small blueprint must remain one bounded work package");
        }
        if (blueprint.cells().size() > MAX_CELLS_PER_PACKAGE) {
            for (ConstructionWorkPackage workPackage : canonical) {
                if (workPackage.cells().size() < MIN_CELLS_PER_LARGE_PACKAGE) {
                    throw new IllegalArgumentException(
                            "large-blueprint work packages must meet the minimum cell count");
                }
            }
        }
        int maximumPackagesForBlueprint = (blueprint.cells().size()
                + MIN_CELLS_PER_LARGE_PACKAGE - 1) / MIN_CELLS_PER_LARGE_PACKAGE;
        if (canonical.size() > maximumPackagesForBlueprint) {
            throw new IllegalArgumentException(
                    "construction work packages exceed the blueprint cell budget");
        }
        validateExactCover(blueprint, canonical);
        Map<ConstructionWorkPackageKey, ConstructionWorkPackage> byKey = indexByKey(canonical);
        List<ConstructionWorkPackageKey> topological = topologicalKeys(canonical, byKey);
        if (topological.size() != canonical.size()) {
            throw new IllegalArgumentException("construction work package dependencies must be acyclic");
        }
        return List.copyOf(canonical);
    }

    private static void validateExactCover(Blueprint blueprint,
            List<ConstructionWorkPackage> workPackages) {
        Map<BlueprintOffset, BlueprintCell> remaining = new HashMap<>();
        for (BlueprintCell cell : blueprint.cells()) {
            remaining.put(cell.offset(), cell);
        }
        for (ConstructionWorkPackage workPackage : workPackages) {
            for (BlueprintCell cell : workPackage.cells()) {
                BlueprintCell expected = remaining.remove(cell.offset());
                if (expected == null || !expected.equals(cell)) {
                    throw new IllegalArgumentException(
                            "work package cells must exactly cover the blueprint content once");
                }
            }
        }
        if (!remaining.isEmpty()) {
            throw new IllegalArgumentException(
                    "work package cells must exactly cover the blueprint content once");
        }
    }

    private static Map<ConstructionWorkPackageKey, ConstructionWorkPackage> indexByKey(
            List<ConstructionWorkPackage> workPackages) {
        Map<ConstructionWorkPackageKey, ConstructionWorkPackage> byKey = new HashMap<>();
        for (ConstructionWorkPackage workPackage : workPackages) {
            ConstructionWorkPackage prior = byKey.put(workPackage.key(), workPackage);
            if (prior != null) {
                throw new IllegalArgumentException("construction work package keys must be unique");
            }
        }
        return byKey;
    }

    private static List<ConstructionWorkPackageKey> topologicalKeys(
            List<ConstructionWorkPackage> workPackages,
            Map<ConstructionWorkPackageKey, ConstructionWorkPackage> byKey) {
        Map<ConstructionWorkPackageKey, Integer> remainingPrerequisites = new HashMap<>();
        Map<ConstructionWorkPackageKey, List<ConstructionWorkPackageKey>> dependents =
                new HashMap<>();
        for (ConstructionWorkPackage workPackage : workPackages) {
            ConstructionWorkPackageKey key = workPackage.key();
            remainingPrerequisites.put(key, workPackage.prerequisites().size());
            dependents.put(key, new ArrayList<>());
        }
        for (ConstructionWorkPackage workPackage : workPackages) {
            for (ConstructionWorkPackageKey prerequisite : workPackage.prerequisites()) {
                if (!byKey.containsKey(prerequisite)) {
                    throw new IllegalArgumentException(
                            "work package prerequisite must belong to the same plan");
                }
                dependents.get(prerequisite).add(workPackage.key());
            }
        }
        PriorityQueue<ConstructionWorkPackageKey> ready = new PriorityQueue<>();
        for (Map.Entry<ConstructionWorkPackageKey, Integer> entry
                : remainingPrerequisites.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<ConstructionWorkPackageKey> result = new ArrayList<>(workPackages.size());
        while (!ready.isEmpty()) {
            ConstructionWorkPackageKey key = ready.remove();
            result.add(key);
            for (ConstructionWorkPackageKey dependent : dependents.get(key)) {
                int remaining = remainingPrerequisites.merge(dependent, -1, Integer::sum);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        return List.copyOf(result);
    }
}
