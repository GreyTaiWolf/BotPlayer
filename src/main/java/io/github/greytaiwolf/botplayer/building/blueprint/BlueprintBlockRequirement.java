package io.github.greytaiwolf.botplayer.building.blueprint;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Objects;

/**
 * One aggregated planned-block requirement.
 *
 * <p>The {@link #blockId()} is a desired block identifier, not an inventory item identifier. A later
 * registry-aware resolver must prove which held item, if any, can place it before material reservation
 * or world interaction occurs.
 */
public record BlueprintBlockRequirement(
        ResourceId blockId,
        BlueprintMaterialClass materialClass,
        int count) {
    public BlueprintBlockRequirement {
        Objects.requireNonNull(blockId, "blockId");
        Objects.requireNonNull(materialClass, "materialClass");
        if (count < 1 || count > Blueprint.MAX_CELLS) {
            throw new IllegalArgumentException(
                    "blueprint block requirement count is outside the bounded blueprint limit");
        }
    }
}
