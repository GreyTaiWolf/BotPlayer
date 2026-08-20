package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import java.util.Objects;

/** Exact candidate construction bounds derived from every cell of one immutable blueprint. */
public record ConstructionSiteBounds(
        ResourceId dimension,
        BlockCoordinates minimum,
        BlockCoordinates maximum) {
    public ConstructionSiteBounds {
        dimension = Objects.requireNonNull(dimension, "dimension");
        minimum = Objects.requireNonNull(minimum, "minimum");
        maximum = Objects.requireNonNull(maximum, "maximum");
        if (minimum.x() > maximum.x()
                || minimum.y() > maximum.y()
                || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException(
                    "construction site bounds minimum must not exceed maximum");
        }
    }

    /** Derives exact translated bounds without reading, loading, or modifying a world. */
    public static ConstructionSiteBounds forBlueprint(
            Blueprint blueprint, ConstructionSiteAnchor anchor) {
        Blueprint checkedBlueprint = Objects.requireNonNull(blueprint, "blueprint");
        ConstructionSiteAnchor checkedAnchor = Objects.requireNonNull(anchor, "anchor");
        BlockCoordinates first = ConstructionSiteCoordinates.translate(
                checkedAnchor.origin(), checkedBlueprint.cells().get(0).offset());
        int minimumX = first.x();
        int minimumY = first.y();
        int minimumZ = first.z();
        int maximumX = first.x();
        int maximumY = first.y();
        int maximumZ = first.z();
        for (BlueprintCell cell : checkedBlueprint.cells()) {
            BlockCoordinates translated = ConstructionSiteCoordinates.translate(
                    checkedAnchor.origin(), cell.offset());
            minimumX = Math.min(minimumX, translated.x());
            minimumY = Math.min(minimumY, translated.y());
            minimumZ = Math.min(minimumZ, translated.z());
            maximumX = Math.max(maximumX, translated.x());
            maximumY = Math.max(maximumY, translated.y());
            maximumZ = Math.max(maximumZ, translated.z());
        }
        return new ConstructionSiteBounds(checkedAnchor.dimension(),
                new BlockCoordinates(minimumX, minimumY, minimumZ),
                new BlockCoordinates(maximumX, maximumY, maximumZ));
    }

    /** Coordinate-only containment within this bounds' already-fixed dimension. */
    public boolean contains(BlockCoordinates position) {
        BlockCoordinates checkedPosition = Objects.requireNonNull(position, "position");
        return checkedPosition.x() >= minimum.x() && checkedPosition.x() <= maximum.x()
                && checkedPosition.y() >= minimum.y() && checkedPosition.y() <= maximum.y()
                && checkedPosition.z() >= minimum.z() && checkedPosition.z() <= maximum.z();
    }

    /** Returns true only when both the candidate dimension and coordinate are within this bounds. */
    public boolean contains(ResourceId candidateDimension, BlockCoordinates position) {
        ResourceId checkedDimension = Objects.requireNonNull(candidateDimension,
                "candidateDimension");
        BlockCoordinates checkedPosition = Objects.requireNonNull(position, "position");
        return dimension.equals(checkedDimension) && contains(checkedPosition);
    }
}
