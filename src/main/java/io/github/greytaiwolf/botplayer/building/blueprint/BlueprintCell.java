package io.github.greytaiwolf.botplayer.building.blueprint;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import java.util.Objects;

/**
 * One desired block state in a blueprint-relative coordinate.
 *
 * <p>This schema intentionally carries no {@code BlockEntity} data, NBT, item stack, post-placement
 * callback, or Minecraft runtime object. Those concerns require separately registered future adapters
 * and real player interaction.
 */
public record BlueprintCell(
        BlueprintOffset offset,
        BlockStateFingerprint expectedState,
        BlueprintPlacementRole role,
        BlueprintReplacePolicy replacePolicy,
        BlueprintMaterialClass materialClass) {
    public BlueprintCell {
        Objects.requireNonNull(offset, "offset");
        Objects.requireNonNull(expectedState, "expectedState");
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(replacePolicy, "replacePolicy");
        Objects.requireNonNull(materialClass, "materialClass");
    }
}
