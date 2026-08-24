package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackage;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackageKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Immutable candidate-site target manifest for one exact work package in one exact site binding.
 *
 * <p>This projection only reuses the binding's package-key-and-offset coordinate fence for the
 * package's immutable cells. It does not read or retain a world survey, assessment freshness,
 * lease, material observation, allocation, reservation, reachability, facing, click surface,
 * placement candidate, Action, Technique, Skill, or world-write authority.
 */
public final class ConstructionWorkPackageSiteTargetProjection {
    private final ConstructionSiteBinding binding;
    private final ConstructionWorkPackageKey workPackageKey;
    private final List<Target> targets;

    private ConstructionWorkPackageSiteTargetProjection(
            ConstructionSiteBinding binding,
            ConstructionWorkPackageKey workPackageKey,
            List<Target> targets) {
        this.binding = Objects.requireNonNull(binding, "binding");
        this.workPackageKey = Objects.requireNonNull(workPackageKey, "workPackageKey");
        ConstructionWorkPackage workPackage = requireWorkPackage(binding, workPackageKey);
        this.targets = canonicalAndValidate(binding, workPackage, targets);
    }

    /**
     * Derives every exact package target from the immutable binding rather than caller coordinates.
     */
    public static ConstructionWorkPackageSiteTargetProjection project(
            ConstructionSiteBinding binding,
            ConstructionWorkPackageKey workPackageKey) {
        ConstructionSiteBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        ConstructionWorkPackageKey checkedKey = Objects.requireNonNull(workPackageKey,
                "workPackageKey");
        ConstructionWorkPackage workPackage = requireWorkPackage(checkedBinding, checkedKey);
        List<Target> targets = new ArrayList<>(workPackage.cells().size());
        for (BlueprintCell cell : workPackage.cells()) {
            targets.add(new Target(cell, checkedBinding.targetPosition(checkedKey, cell.offset())));
        }
        return new ConstructionWorkPackageSiteTargetProjection(checkedBinding, checkedKey, targets);
    }

    /** Returns the exact immutable candidate binding that supplied the coordinates. */
    public ConstructionSiteBinding binding() {
        return binding;
    }

    /** Returns the exact immutable package key that supplied the package cells. */
    public ConstructionWorkPackageKey workPackageKey() {
        return workPackageKey;
    }

    /** Returns canonical package-cell order with coordinates re-derived from the binding. */
    public List<Target> targets() {
        return targets;
    }

    /** Returns the exact bounded target count, never a progress or placement count. */
    public int targetCount() {
        return targets.size();
    }

    private static ConstructionWorkPackage requireWorkPackage(
            ConstructionSiteBinding binding,
            ConstructionWorkPackageKey workPackageKey) {
        for (ConstructionWorkPackage workPackage : binding.workPlan().workPackages()) {
            if (workPackage.key().equals(workPackageKey)) {
                return workPackage;
            }
        }
        throw new IllegalArgumentException(
                "work package key must belong to the exact candidate site binding");
    }

    private static List<Target> canonicalAndValidate(
            ConstructionSiteBinding binding,
            ConstructionWorkPackage workPackage,
            List<Target> supplied) {
        Objects.requireNonNull(supplied, "targets");
        if (supplied.size() != workPackage.cells().size()) {
            throw new IllegalArgumentException(
                    "work package site targets must exactly cover the bound package cells");
        }
        List<Target> canonical = new ArrayList<>(supplied.size());
        for (int index = 0; index < workPackage.cells().size(); index++) {
            BlueprintCell expectedCell = workPackage.cells().get(index);
            Target target = Objects.requireNonNull(supplied.get(index), "work package site target");
            BlockCoordinates expectedPosition = binding.targetPosition(workPackage.key(),
                    expectedCell.offset());
            if (!target.cell().equals(expectedCell)
                    || !target.targetPosition().equals(expectedPosition)) {
                throw new IllegalArgumentException(
                        "work package site target must exactly match the bound package cell and coordinate");
            }
            canonical.add(target);
        }
        return List.copyOf(canonical);
    }

    /** One immutable blueprint cell and its coordinate in the enclosing candidate binding. */
    public record Target(BlueprintCell cell, BlockCoordinates targetPosition) {
        public Target {
            cell = Objects.requireNonNull(cell, "cell");
            targetPosition = Objects.requireNonNull(targetPosition, "targetPosition");
        }
    }
}
