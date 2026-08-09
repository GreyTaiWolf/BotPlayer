package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.Tiers;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MinecraftBasicEquipmentPlannerTest {
    private static final String DIGEST = "c".repeat(64);

    @Test
    void exactVanillaToolClassesMapToOneDeclaredToolKind() {
        Assertions.assertEquals(Optional.of(ToolKind.PICKAXE),
                MinecraftBasicEquipmentPlanner
                        .vanillaToolKindForClass(PickaxeItem.class));
        Assertions.assertEquals(Optional.of(ToolKind.AXE),
                MinecraftBasicEquipmentPlanner
                        .vanillaToolKindForClass(AxeItem.class));
        Assertions.assertEquals(Optional.of(ToolKind.SHOVEL),
                MinecraftBasicEquipmentPlanner
                        .vanillaToolKindForClass(ShovelItem.class));
        Assertions.assertEquals(Optional.of(ToolKind.HOE),
                MinecraftBasicEquipmentPlanner
                        .vanillaToolKindForClass(HoeItem.class));
        Assertions.assertEquals(Optional.of(ToolKind.WEAPON),
                MinecraftBasicEquipmentPlanner
                        .vanillaToolKindForClass(SwordItem.class));
        Assertions.assertTrue(MinecraftBasicEquipmentPlanner
                .vanillaToolKindForClass(Item.class).isEmpty());
    }

    @Test
    void vanillaTierRanksPreserveMiningCapabilityBeforeSpeed() {
        Assertions.assertEquals(0,
                MinecraftBasicEquipmentPlanner
                        .vanillaTierRank(Tiers.WOOD));
        Assertions.assertEquals(0,
                MinecraftBasicEquipmentPlanner
                        .vanillaTierRank(Tiers.GOLD));
        Assertions.assertEquals(1,
                MinecraftBasicEquipmentPlanner
                        .vanillaTierRank(Tiers.STONE));
        Assertions.assertEquals(2,
                MinecraftBasicEquipmentPlanner
                        .vanillaTierRank(Tiers.IRON));
        Assertions.assertEquals(3,
                MinecraftBasicEquipmentPlanner
                        .vanillaTierRank(Tiers.DIAMOND));
        Assertions.assertEquals(4,
                MinecraftBasicEquipmentPlanner
                        .vanillaTierRank(Tiers.NETHERITE));
    }

    @Test
    void toolSelectionFreezesSourceAndSelectedHotbarMapping() {
        EquipmentCandidate candidate = tool(14, ToolKind.PICKAXE,
                2, 6.0D, 200, true, false, false);

        MinecraftBasicEquipmentPlanner.ToolSelection selection =
                new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(3, 14, candidate.itemFingerprint()),
                        ToolKind.PICKAXE,
                        14, 3, candidate);

        Assertions.assertEquals(14, selection.sourceInventorySlot());
        Assertions.assertEquals(3, selection.targetHotbarSlot());
        Assertions.assertEquals(ToolKind.PICKAXE,
                selection.requestedKind());
        Assertions.assertTrue(selection.requiresInventorySwap());
        Assertions.assertEquals(candidate, selection.candidate());
    }

    @Test
    void toolSelectionRecognizesAlreadySelectedSourceAsNoSwap() {
        MinecraftBasicEquipmentPlanner.ToolSelection selection =
                new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(3, 3,
                                fingerprint("minecraft:iron_pickaxe")),
                        ToolKind.PICKAXE,
                        3, 3,
                        tool(3, ToolKind.PICKAXE, 2, 6.0D, 200,
                                true, false, false));

        Assertions.assertFalse(selection.requiresInventorySwap());
    }

    @Test
    void toolSelectionRejectsNonCarriedWrongKindAndUnsafeCandidates() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(0, 36,
                                fingerprint("minecraft:iron_pickaxe")),
                        ToolKind.PICKAXE,
                        36, 0,
                        tool(36, ToolKind.PICKAXE, 2, 6.0D, 100,
                                true, false, false)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(0, 2,
                                fingerprint("minecraft:iron_pickaxe")),
                        ToolKind.PICKAXE,
                        2, 9,
                        tool(2, ToolKind.PICKAXE, 2, 6.0D, 100,
                                true, false, false)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(1, 2, fingerprint("minecraft:torch")),
                        ToolKind.PICKAXE,
                        2, 1,
                        offhand(2, false, 0, true, false, false)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(1, 2,
                                fingerprint("minecraft:iron_pickaxe")),
                        ToolKind.PICKAXE,
                        2, 1,
                        tool(2, ToolKind.AXE, 2, 6.0D, 100,
                                true, false, false)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(1, 2,
                                fingerprint("minecraft:iron_pickaxe")),
                        ToolKind.PICKAXE,
                        2, 1,
                        tool(2, ToolKind.PICKAXE, 2, 6.0D, 0,
                                true, false, false)));
    }

    @Test
    void explicitOffhandSelectionBindsSourceFingerprintAndSlotForty() {
        EquipmentCandidate candidate = offhand(12, false, 0,
                true, false, false);
        ItemStackFingerprint requested = candidate.itemFingerprint();

        MinecraftBasicEquipmentPlanner.OffhandSelection selection =
                new MinecraftBasicEquipmentPlanner.OffhandSelection(
                        snapshot(0, 12, requested),
                        12,
                        PlayerInventoryMenuLayout
                                .OFFHAND_INVENTORY_SLOT,
                        requested,
                        candidate);

        Assertions.assertEquals(12, selection.sourceInventorySlot());
        Assertions.assertEquals(40, selection.targetInventorySlot());
        Assertions.assertEquals(requested, selection.expectedItem());
        Assertions.assertEquals(candidate, selection.candidate());
    }

    @Test
    void offhandSelectionRejectsImplicitOrUnsafeReplacement() {
        EquipmentCandidate candidate = offhand(12, false, 0,
                true, false, false);
        ItemStackFingerprint different = fingerprint("minecraft:map");

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.OffhandSelection(
                        snapshot(0, 12, candidate.itemFingerprint()),
                        12, 39, candidate.itemFingerprint(), candidate));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.OffhandSelection(
                        snapshot(0, 12, candidate.itemFingerprint()),
                        12,
                        PlayerInventoryMenuLayout
                                .OFFHAND_INVENTORY_SLOT,
                        different,
                        candidate));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.OffhandSelection(
                        snapshot(0, 40, candidate.itemFingerprint()),
                        40,
                        PlayerInventoryMenuLayout
                                .OFFHAND_INVENTORY_SLOT,
                        candidate.itemFingerprint(), candidate));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.OffhandSelection(
                        snapshot(0, 12, candidate.itemFingerprint()),
                        12,
                        PlayerInventoryMenuLayout
                                .OFFHAND_INVENTORY_SLOT,
                        candidate.itemFingerprint(),
                        offhand(12, false, 0, true, true, false)));
    }

    @Test
    void selectionsRejectStaleOrCursorCarryingMenuSnapshots() {
        EquipmentCandidate tool = tool(2, ToolKind.PICKAXE,
                2, 6.0D, 100, true, false, false);
        EquipmentCandidate offhand = offhand(12, false, 0,
                true, false, false);

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.ToolSelection(
                        snapshot(1, 2, fingerprint("minecraft:torch")),
                        ToolKind.PICKAXE,
                        2, 1, tool));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new MinecraftBasicEquipmentPlanner.OffhandSelection(
                        snapshot(0, 12, offhand.itemFingerprint(),
                                fingerprint("minecraft:torch")),
                        12,
                        PlayerInventoryMenuLayout
                                .OFFHAND_INVENTORY_SLOT,
                        offhand.itemFingerprint(), offhand));
    }

    private static EquipmentCandidate tool(
            int slot,
            ToolKind kind,
            int tier,
            double efficiency,
            int remainingDurability,
            boolean canEquip,
            boolean targetBlocked,
            boolean candidateBinds) {
        return new EquipmentCandidate(
                slot,
                fingerprint("minecraft:iron_pickaxe"),
                EquipmentSlotKind.MAIN_HAND_TOOL,
                Optional.of(kind),
                tier,
                0.0D,
                0.0D,
                efficiency,
                remainingDurability,
                true,
                canEquip,
                targetBlocked,
                candidateBinds);
    }

    private static EquipmentCandidate offhand(
            int slot,
            boolean damageable,
            int remainingDurability,
            boolean canEquip,
            boolean targetBlocked,
            boolean candidateBinds) {
        return new EquipmentCandidate(
                slot,
                fingerprint("minecraft:torch"),
                EquipmentSlotKind.OFFHAND,
                Optional.empty(),
                0,
                0.0D,
                0.0D,
                0.0D,
                remainingDurability,
                damageable,
                canEquip,
                targetBlocked,
                candidateBinds);
    }

    private static ItemStackFingerprint fingerprint(String itemId) {
        return ItemStackFingerprint.of(new ResourceId(itemId), 1, 0,
                DIGEST);
    }

    private static InventoryMenuSnapshot snapshot(
            int selectedHotbar,
            int inventorySlot,
            ItemStackFingerprint item) {
        return snapshot(selectedHotbar, inventorySlot, item,
                ItemStackFingerprint.empty());
    }

    private static InventoryMenuSnapshot snapshot(
            int selectedHotbar,
            int inventorySlot,
            ItemStackFingerprint item,
            ItemStackFingerprint cursor) {
        List<ItemStackFingerprint> slots = new ArrayList<>(
                PlayerInventoryMenuLayout.INVENTORY_SLOT_COUNT);
        for (int slot = 0;
                slot < PlayerInventoryMenuLayout.INVENTORY_SLOT_COUNT;
                slot++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(inventorySlot, item);
        return new InventoryMenuSnapshot(
                1, 1, selectedHotbar, cursor, slots);
    }
}
