package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackage;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackageKey;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable candidate-site binding for one exact A1 construction work plan.
 *
 * <p>This validates only a coordinate translation and the full immutable work-package identity.
 * It is not a site survey, accepted build area, material lease, checkpoint, placement plan, or
 * authority to load or modify a Minecraft world.
 */
public record ConstructionSiteBinding(
        UUID siteId,
        ConstructionWorkPlan workPlan,
        ConstructionSiteAnchor anchor,
        ConstructionSiteBounds constructionBounds) {
    public ConstructionSiteBinding {
        requireNonZero(siteId, "siteId");
        workPlan = Objects.requireNonNull(workPlan, "workPlan");
        anchor = Objects.requireNonNull(anchor, "anchor");
        constructionBounds = Objects.requireNonNull(constructionBounds,
                "constructionBounds");
        ConstructionSiteBounds expected = ConstructionSiteBounds.forBlueprint(
                workPlan.blueprint(), anchor);
        if (!expected.equals(constructionBounds)) {
            throw new IllegalArgumentException(
                    "construction site bounds must match the exact bound work plan and anchor");
        }
    }

    /** Binds a caller-provided candidate identity to derived, non-forgeable construction bounds. */
    public static ConstructionSiteBinding bind(
            UUID siteId, ConstructionWorkPlan workPlan, ConstructionSiteAnchor anchor) {
        ConstructionWorkPlan checkedWorkPlan = Objects.requireNonNull(workPlan, "workPlan");
        ConstructionSiteAnchor checkedAnchor = Objects.requireNonNull(anchor, "anchor");
        return new ConstructionSiteBinding(siteId, checkedWorkPlan, checkedAnchor,
                ConstructionSiteBounds.forBlueprint(checkedWorkPlan.blueprint(), checkedAnchor));
    }

    /** Returns true only for a complete package key that belongs to this exact immutable plan. */
    public boolean binds(ConstructionWorkPackageKey key) {
        ConstructionWorkPackageKey checkedKey = Objects.requireNonNull(key, "key");
        for (ConstructionWorkPackage workPackage : workPlan.workPackages()) {
            if (workPackage.key().equals(checkedKey)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Translates one actual blueprint cell and rejects arbitrary nearby offsets.
     *
     * <p>The returned coordinate remains in {@link #anchor()}'s dimension; this DTO does not
     * query whether that coordinate is loaded, replaceable, protected, reachable, or safe.
     */
    public BlockCoordinates targetPosition(BlueprintOffset offset) {
        BlueprintOffset checkedOffset = Objects.requireNonNull(offset, "offset");
        for (BlueprintCell cell : workPlan.blueprint().cells()) {
            if (cell.offset().equals(checkedOffset)) {
                return ConstructionSiteCoordinates.translate(anchor.origin(), checkedOffset);
            }
        }
        throw new IllegalArgumentException("target offset must belong to the bound blueprint");
    }

    /**
     * Translates one cell only when it belongs to the supplied exact work package.
     *
     * <p>Future package-level consumers must use this overload instead of combining a package key
     * and a plan-level {@link #targetPosition(BlueprintOffset)} lookup independently.
     */
    public BlockCoordinates targetPosition(
            ConstructionWorkPackageKey key, BlueprintOffset offset) {
        ConstructionWorkPackageKey checkedKey = Objects.requireNonNull(key, "key");
        BlueprintOffset checkedOffset = Objects.requireNonNull(offset, "offset");
        for (ConstructionWorkPackage workPackage : workPlan.workPackages()) {
            if (!workPackage.key().equals(checkedKey)) {
                continue;
            }
            for (BlueprintCell cell : workPackage.cells()) {
                if (cell.offset().equals(checkedOffset)) {
                    return ConstructionSiteCoordinates.translate(anchor.origin(), checkedOffset);
                }
            }
            throw new IllegalArgumentException(
                    "target offset must belong to the bound work package");
        }
        throw new IllegalArgumentException("work package key must belong to the bound work plan");
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
    }
}
