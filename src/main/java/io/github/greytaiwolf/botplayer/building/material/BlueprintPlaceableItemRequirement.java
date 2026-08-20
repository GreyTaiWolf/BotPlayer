package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import java.util.Objects;

/**
 * One declared item quantity for a material class.
 *
 * <p>The item id is copied from explicit {@link PlaceableItemEvidence} after its exact target state has
 * been resolved. This DTO never derives an item id from a block id or establishes availability.
 */
public record BlueprintPlaceableItemRequirement(
        ResourceId placeableItemId,
        BlueprintMaterialClass materialClass,
        int count) {
    public BlueprintPlaceableItemRequirement {
        placeableItemId = Objects.requireNonNull(placeableItemId, "placeableItemId");
        materialClass = Objects.requireNonNull(materialClass, "materialClass");
        if (count < 1 || count > Blueprint.MAX_CELLS) {
            throw new IllegalArgumentException(
                    "blueprint placeable item requirement count is outside the bounded blueprint limit");
        }
    }
}
