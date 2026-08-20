package io.github.greytaiwolf.botplayer.building.construction;

import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintBlockRequirements;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * One bounded, immutable, non-executable partition of a blueprint.
 *
 * <p>A package has no site, world position, material reservation, placement candidate, Technique,
 * Action, checkpoint, state transition, or ownership proof. Its cells describe only the exact
 * blueprint content that a future, separately authorized construction route must revalidate.
 */
public record ConstructionWorkPackage(
        ConstructionWorkPackageKey key,
        List<BlueprintCell> cells,
        List<ConstructionWorkPackageKey> prerequisites) {
    public static final int MAX_CELLS = 64;
    public static final int MAX_PREREQUISITES = ConstructionWorkPackageKey.MAX_WORK_PACKAGES - 1;

    private static final Comparator<BlueprintCell> CELL_ORDER =
            Comparator.comparing(BlueprintCell::offset);

    public ConstructionWorkPackage {
        Objects.requireNonNull(key, "key");
        cells = canonicalCells(cells);
        prerequisites = canonicalPrerequisites(key, prerequisites);
    }

    /**
     * Returns structural planned-block requirements for this package only.
     *
     * <p>As with the blueprint-level aggregate, desired block IDs are not inventory item IDs and
     * this method has no reservation or world authority.
     */
    public BlueprintBlockRequirements plannedBlockRequirements() {
        return BlueprintBlockRequirements.fromCells(cells);
    }

    private static List<BlueprintCell> canonicalCells(List<BlueprintCell> supplied) {
        Objects.requireNonNull(supplied, "cells");
        if (supplied.isEmpty()) {
            throw new IllegalArgumentException("work package cells must not be empty");
        }
        List<BlueprintCell> canonical = new ArrayList<>(Math.min(supplied.size(), MAX_CELLS));
        for (BlueprintCell cell : supplied) {
            canonical.add(Objects.requireNonNull(cell, "work package cell"));
            if (canonical.size() > MAX_CELLS) {
                throw new IllegalArgumentException("work package cells exceed the bounded limit");
            }
        }
        canonical.sort(CELL_ORDER);
        BlueprintCell previous = null;
        for (BlueprintCell cell : canonical) {
            if (previous != null && previous.offset().equals(cell.offset())) {
                throw new IllegalArgumentException("work package cells must not share an offset");
            }
            previous = cell;
        }
        return List.copyOf(canonical);
    }

    private static List<ConstructionWorkPackageKey> canonicalPrerequisites(
            ConstructionWorkPackageKey key, List<ConstructionWorkPackageKey> supplied) {
        Objects.requireNonNull(supplied, "prerequisites");
        List<ConstructionWorkPackageKey> canonical = new ArrayList<>(
                Math.min(supplied.size(), MAX_PREREQUISITES));
        for (ConstructionWorkPackageKey prerequisite : supplied) {
            ConstructionWorkPackageKey required = Objects.requireNonNull(prerequisite,
                    "work package prerequisite");
            if (required.equals(key)) {
                throw new IllegalArgumentException("work package must not depend on itself");
            }
            canonical.add(required);
            if (canonical.size() > MAX_PREREQUISITES) {
                throw new IllegalArgumentException(
                        "work package prerequisites exceed the bounded limit");
            }
        }
        canonical.sort(Comparator.naturalOrder());
        ConstructionWorkPackageKey previous = null;
        for (ConstructionWorkPackageKey prerequisite : canonical) {
            if (previous != null && previous.equals(prerequisite)) {
                throw new IllegalArgumentException("work package prerequisites must be unique");
            }
            previous = prerequisite;
        }
        return List.copyOf(canonical);
    }
}
