package io.github.greytaiwolf.botplayer.building.material.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.material.BlueprintPlaceableItemEvidence;
import io.github.greytaiwolf.botplayer.building.material.PlaceableItemEvidence;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * Server-thread registry check for a complete, explicit blueprint item declaration.
 *
 * <p>This adapter accepts no partial mapping and never derives an item id from a block id. It only
 * checks whether every item already declared by {@link BlueprintPlaceableItemEvidence} currently
 * resolves through the native item registry to a non-air {@link BlockItem} whose block's
 * {@linkplain Block#defaultBlockState() default state} exactly equals that declaration's full
 * fingerprint. It does not establish vanilla placement behavior for a click context, item
 * availability, a reservation, a site lease, or any execution authority.
 */
public final class MinecraftBlueprintPlaceableItemEvidenceValidator {
    private MinecraftBlueprintPlaceableItemEvidenceValidator() {}

    /**
     * Returns true only when every explicit declaration matches the current native registry's
     * non-air default block-state candidate.
     *
     * <p>The call must occur on the authoritative server thread. A malformed, missing, non-block,
     * air, or property-encoding-failing registry entry returns false; no registry guess or partial
     * state match is accepted. The result is an instantaneous observation and must be rechecked by
     * any future side-effecting construction path.
     *
     * @throws IllegalStateException if called off the supplied server's authoritative thread
     */
    public static boolean matchesRegisteredDefaultBlockItems(
            MinecraftServer server, BlueprintPlaceableItemEvidence declaredEvidence) {
        MinecraftServer checkedServer = Objects.requireNonNull(server, "server");
        BlueprintPlaceableItemEvidence checkedEvidence = Objects.requireNonNull(
                declaredEvidence, "declaredEvidence");
        if (!checkedServer.isSameThread()) {
            throw new IllegalStateException(
                    "blueprint item registry validation requires the authoritative server thread");
        }
        for (PlaceableItemEvidence entry : checkedEvidence.evidence()) {
            if (!matchesRegisteredDefaultBlockItem(entry)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchesRegisteredDefaultBlockItem(PlaceableItemEvidence entry) {
        try {
            ResourceLocation itemId = resourceLocation(entry.placeableItemId());
            Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
            if (!(item instanceof BlockItem blockItem)
                    || !itemId.equals(BuiltInRegistries.ITEM.getKey(item))) {
                return false;
            }
            BlockState defaultState = blockItem.getBlock().defaultBlockState();
            return !defaultState.isAir()
                    && entry.targetState().equals(fingerprint(defaultState));
        } catch (RuntimeException exception) {
            // A missing or malformed third-party registry entry cannot become construction evidence.
            return false;
        }
    }

    private static ResourceLocation resourceLocation(ResourceId resourceId) {
        String value = resourceId.value();
        int separator = value.indexOf(':');
        return ResourceLocation.fromNamespaceAndPath(
                value.substring(0, separator), value.substring(separator + 1));
    }

    private static BlockStateFingerprint fingerprint(BlockState state) {
        ResourceId blockId = new ResourceId(BuiltInRegistries.BLOCK
                .getKey(state.getBlock()).toString());
        Map<String, String> properties = new TreeMap<>();
        state.getValues().forEach((property, value) -> properties.put(
                property.getName(), propertyValueName(property, value)));
        return new BlockStateFingerprint(blockId, properties);
    }

    /** Uses Minecraft's property serializer instead of an enum or value {@code toString()}. */
    @SuppressWarnings({"rawtypes", "unchecked"})
    private static String propertyValueName(Property property, Comparable value) {
        return property.getName(value);
    }
}
