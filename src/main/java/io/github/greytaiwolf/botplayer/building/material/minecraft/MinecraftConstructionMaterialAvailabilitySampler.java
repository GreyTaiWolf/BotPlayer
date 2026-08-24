package io.github.greytaiwolf.botplayer.building.material.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.building.material.BlueprintPlaceableItemEvidence;
import io.github.greytaiwolf.botplayer.building.material.BlueprintPlaceableItemRequirement;
import io.github.greytaiwolf.botplayer.building.material.ConstructionMaterialAvailability;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * Server-thread, read-only observation of declared construction materials in one bot's own inventory.
 *
 * <p>The adapter first requires the existing A4-R1 native registry/default-state validation. It then
 * accepts only an active, exact native inventory menu with an empty cursor and counts default-item
 * fingerprints only in main-inventory and hotbar slots. It never reads a world or container, changes an
 * inventory, acquires a reservation, or invokes an Action, Technique, Skill, lifecycle route, or
 * placement behavior.
 */
public final class MinecraftConstructionMaterialAvailabilitySampler {
    private MinecraftConstructionMaterialAvailabilitySampler() {}

    /**
     * Samples one bounded point-in-time material observation for an active bot body.
     *
     * <p>Off-thread use fails before registry or inventory access. A bad explicit declaration is reported
     * as {@link ConstructionMaterialAvailability.Status#UNAVAILABLE_REGISTRY}; an inactive, malformed, or
     * cursor-carrying native menu is reported as
     * {@link ConstructionMaterialAvailability.Status#UNAVAILABLE_MENU}. Neither status is a zero-stock
     * result.
     *
     * @throws IllegalStateException if the bot has no authoritative server thread, its tick is invalid, or
     *     its runtime identity cannot be safely bound
     */
    public static ConstructionMaterialAvailability sample(
            BotServerPlayer player,
            BlueprintPlaceableItemEvidence declaredEvidence) {
        BotServerPlayer checkedPlayer = Objects.requireNonNull(player, "player");
        BlueprintPlaceableItemEvidence checkedEvidence = Objects.requireNonNull(
                declaredEvidence, "declaredEvidence");
        MinecraftServer server = requireServerThread(checkedPlayer);
        long observedTick = server.getTickCount();
        if (observedTick < 0L) {
            throw new IllegalStateException("server tick must not be negative");
        }

        BotRuntimeHandle handle = checkedPlayer.runtimeHandle();
        UUID botId = handle.botId();
        long botGeneration = handle.generation();
        if (!botId.equals(checkedPlayer.getUUID())
                || botGeneration < 1L
                || handle.player().orElse(null) != checkedPlayer) {
            throw new IllegalStateException(
                    "material availability requires a live bot runtime identity");
        }

        if (!MinecraftBlueprintPlaceableItemEvidenceValidator
                .matchesRegisteredDefaultBlockItems(server, checkedEvidence)) {
            return ConstructionMaterialAvailability.unavailableRegistry(
                    botId, botGeneration, checkedEvidence, observedTick);
        }

        Map<ResourceId, Integer> requiredByItem = aggregateRequiredItems(checkedEvidence);
        Map<ResourceId, ItemStackFingerprint> defaultFingerprints = defaultFingerprints(
                checkedPlayer, requiredByItem.keySet());
        if (defaultFingerprints == null) {
            return ConstructionMaterialAvailability.unavailableRegistry(
                    botId, botGeneration, checkedEvidence, observedTick);
        }

        InventoryMenuSnapshot menuSnapshot = captureNativeInventoryMenu(checkedPlayer);
        if (menuSnapshot == null) {
            return ConstructionMaterialAvailability.unavailableMenu(
                    botId, botGeneration, checkedEvidence, observedTick);
        }

        List<ConstructionMaterialAvailability.Finding> findings = new ArrayList<>(
                requiredByItem.size());
        boolean hasShortage = false;
        try {
            for (Map.Entry<ResourceId, Integer> entry : requiredByItem.entrySet()) {
                int availableCount = countMatchingMainOrHotbarSlots(menuSnapshot,
                        defaultFingerprints.get(entry.getKey()));
                int shortageCount = Math.max(0, entry.getValue() - availableCount);
                findings.add(new ConstructionMaterialAvailability.Finding(entry.getKey(),
                        entry.getValue(), availableCount, shortageCount));
                hasShortage |= shortageCount > 0;
            }
        } catch (ArithmeticException exception) {
            // Never turn an unexpected inventory-count overflow into a fabricated shortage.
            return ConstructionMaterialAvailability.unavailableMenu(
                    botId, botGeneration, checkedEvidence, observedTick);
        }

        return new ConstructionMaterialAvailability(botId, botGeneration, checkedEvidence,
                observedTick,
                hasShortage
                        ? ConstructionMaterialAvailability.Status.SHORTAGE
                        : ConstructionMaterialAvailability.Status.AVAILABLE,
                java.util.Optional.of(new ConstructionMaterialAvailability.InventoryMenuFence(
                        menuSnapshot.containerId(), menuSnapshot.stateId(),
                        menuSnapshot.selectedHotbar())),
                findings);
    }

    private static Map<ResourceId, Integer> aggregateRequiredItems(
            BlueprintPlaceableItemEvidence declaredEvidence) {
        Map<ResourceId, Integer> requiredByItem = new LinkedHashMap<>();
        for (BlueprintPlaceableItemRequirement requirement : declaredEvidence
                .declaredItemRequirements().entries()) {
            requiredByItem.merge(requirement.placeableItemId(), requirement.count(), Math::addExact);
        }
        return requiredByItem;
    }

    private static Map<ResourceId, ItemStackFingerprint> defaultFingerprints(
            BotServerPlayer player, Iterable<ResourceId> itemIds) {
        Map<ResourceId, ItemStackFingerprint> fingerprints = new LinkedHashMap<>();
        try {
            for (ResourceId itemId : itemIds) {
                ResourceLocation resourceLocation = resourceLocation(itemId);
                Item item = BuiltInRegistries.ITEM.getOptional(resourceLocation).orElse(null);
                if (item == null || !resourceLocation.equals(BuiltInRegistries.ITEM.getKey(item))) {
                    return null;
                }
                ItemStackFingerprint fingerprint = MinecraftActionSnapshot.item(player,
                        new ItemStack(item));
                if (fingerprint.isEmpty()
                        || !fingerprint.itemId().orElseThrow().equals(itemId)) {
                    return null;
                }
                fingerprints.put(itemId, fingerprint);
            }
            return Map.copyOf(fingerprints);
        } catch (RuntimeException exception) {
            // A registry/component encoding failure is not evidence that an item is usable.
            return null;
        }
    }

    private static InventoryMenuSnapshot captureNativeInventoryMenu(BotServerPlayer player) {
        try {
            if (player.containerMenu != player.inventoryMenu
                    || player.inventoryMenu.getClass() != InventoryMenu.class
                    || !player.inventoryMenu.stillValid(player)
                    || player.inventoryMenu.slots.size()
                            != MenuFamily.INVENTORY_2X2.slotCount()) {
                return null;
            }
            InventoryMenuSnapshot snapshot = MinecraftActionSnapshot.inventoryMenu(player);
            return snapshot.cursor().isEmpty() ? snapshot : null;
        } catch (RuntimeException exception) {
            // A replaced or malformed native menu must remain unavailable rather than look empty.
            return null;
        }
    }

    private static int countMatchingMainOrHotbarSlots(
            InventoryMenuSnapshot menuSnapshot, ItemStackFingerprint expectedFingerprint) {
        ItemStackFingerprint expected = Objects.requireNonNull(expectedFingerprint,
                "expectedFingerprint");
        int count = 0;
        for (int inventorySlot = 0;
                inventorySlot < PlayerInventoryMenuLayout.INVENTORY_SLOT_COUNT;
                inventorySlot++) {
            if (!PlayerInventoryMenuLayout.isMainOrHotbarInventorySlot(inventorySlot)) {
                continue;
            }
            ItemStackFingerprint actual = menuSnapshot.itemAt(inventorySlot);
            if (actual.sameItemAndComponents(expected)) {
                count = Math.addExact(count, actual.count());
            }
        }
        return count;
    }

    private static ResourceLocation resourceLocation(ResourceId resourceId) {
        String value = resourceId.value();
        int separator = value.indexOf(':');
        return ResourceLocation.fromNamespaceAndPath(
                value.substring(0, separator), value.substring(separator + 1));
    }

    private static MinecraftServer requireServerThread(BotServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "construction material availability requires the authoritative server thread");
        }
        return server;
    }
}
