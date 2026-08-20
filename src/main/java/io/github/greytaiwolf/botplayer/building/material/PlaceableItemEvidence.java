package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Objects;

/**
 * An explicit declaration that one exact target state is associated with one placeable item id.
 *
 * <p>This is deliberately only caller-supplied evidence. It does not infer an item id from a block id,
 * validate a registry relation, or prove that the item is available.
 */
public record PlaceableItemEvidence(
        BlockStateFingerprint targetState,
        ResourceId placeableItemId) {
    public PlaceableItemEvidence {
        targetState = Objects.requireNonNull(targetState, "targetState");
        placeableItemId = Objects.requireNonNull(placeableItemId, "placeableItemId");
    }
}
