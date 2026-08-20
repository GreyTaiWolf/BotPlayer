package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import java.util.Objects;

/** Checked coordinate translation shared by the candidate-site DTOs. */
final class ConstructionSiteCoordinates {
    private ConstructionSiteCoordinates() {
        throw new AssertionError("No instances");
    }

    static BlockCoordinates translate(BlockCoordinates origin, BlueprintOffset offset) {
        BlockCoordinates checkedOrigin = Objects.requireNonNull(origin, "origin");
        BlueprintOffset checkedOffset = Objects.requireNonNull(offset, "offset");
        try {
            return new BlockCoordinates(
                    Math.addExact(checkedOrigin.x(), checkedOffset.x()),
                    Math.addExact(checkedOrigin.y(), checkedOffset.y()),
                    Math.addExact(checkedOrigin.z(), checkedOffset.z()));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("construction site coordinate translation overflows",
                    exception);
        }
    }
}
