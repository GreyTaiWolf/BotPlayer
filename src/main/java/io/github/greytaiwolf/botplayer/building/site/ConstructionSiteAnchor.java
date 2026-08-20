package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Objects;

/**
 * Candidate world-coordinate translation for blueprint offset {@code (0, 0, 0)}.
 *
 * <p>This is not a loaded-world observation, approval, lease, placement permission, or claim
 * that the anchor is safe to build at. It contains only immutable dimension and coordinate data
 * so a future survey can bind its result to one exact candidate.
 */
public record ConstructionSiteAnchor(ResourceId dimension, BlockCoordinates origin) {
    public ConstructionSiteAnchor {
        dimension = Objects.requireNonNull(dimension, "dimension");
        origin = Objects.requireNonNull(origin, "origin");
    }
}
