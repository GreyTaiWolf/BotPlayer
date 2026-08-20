package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.HashSet;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlayerInventoryMenuLayoutTest {
    @Test
    void mapsAllFortyOneInventorySlotsRoundTrip() {
        Set<Integer> mappedMenuSlots = new HashSet<>();

        for (int inventorySlot = 0;
                inventorySlot
                        < PlayerInventoryMenuLayout
                                .INVENTORY_SLOT_COUNT;
                inventorySlot++) {
            int menuSlot =
                    PlayerInventoryMenuLayout
                            .menuSlotForInventorySlot(
                                    inventorySlot);
            mappedMenuSlots.add(menuSlot);
            Assertions.assertEquals(
                    OptionalInt.of(inventorySlot),
                    PlayerInventoryMenuLayout
                            .inventorySlotForMenuSlot(menuSlot));
            Assertions.assertTrue(
                    PlayerInventoryMenuLayout
                            .roleOfMenuSlot(menuSlot)
                            .inventoryBacked());
        }

        Assertions.assertEquals(41, mappedMenuSlots.size());
        Assertions.assertFalse(mappedMenuSlots.contains(0));
        Assertions.assertFalse(mappedMenuSlots.contains(4));
    }

    @Test
    void assignsEveryNativeMenuSlotItsExactRole() {
        for (int menuSlot = 0; menuSlot <= 45; menuSlot++) {
            InventoryMenuSlotRole expected;
            if (menuSlot <= 4) {
                expected =
                        InventoryMenuSlotRole.CRAFT_READ_ONLY;
            } else if (menuSlot <= 8) {
                expected = InventoryMenuSlotRole.ARMOR;
            } else if (menuSlot <= 35) {
                expected = InventoryMenuSlotRole.MAIN;
            } else if (menuSlot <= 44) {
                expected = InventoryMenuSlotRole.HOTBAR;
            } else {
                expected = InventoryMenuSlotRole.OFFHAND;
            }
            Assertions.assertEquals(
                    expected,
                    PlayerInventoryMenuLayout
                            .roleOfMenuSlot(menuSlot));
        }

        Assertions.assertEquals(
                OptionalInt.empty(),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(0));
        Assertions.assertEquals(
                OptionalInt.empty(),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(4));
        Assertions.assertEquals(
                OptionalInt.of(39),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(5));
        Assertions.assertEquals(
                OptionalInt.of(36),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(8));
        Assertions.assertEquals(
                OptionalInt.of(0),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(36));
        Assertions.assertEquals(
                OptionalInt.of(8),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(44));
        Assertions.assertEquals(
                OptionalInt.of(40),
                PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(45));
    }

    @Test
    void rejectsSlotsOutsideTheNativeLayout() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PlayerInventoryMenuLayout
                        .menuSlotForInventorySlot(-1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PlayerInventoryMenuLayout
                        .menuSlotForInventorySlot(41));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PlayerInventoryMenuLayout
                        .roleOfMenuSlot(-1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PlayerInventoryMenuLayout
                        .inventorySlotForMenuSlot(46));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PlayerInventoryMenuLayout
                        .isMainOrHotbarInventorySlot(-1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> PlayerInventoryMenuLayout
                        .isMainOrHotbarInventorySlot(41));
    }

    @Test
    void distinguishesOrdinaryCarriedReturnStorageFromEquipmentSlots() {
        Assertions.assertTrue(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(0));
        Assertions.assertTrue(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(8));
        Assertions.assertTrue(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(9));
        Assertions.assertTrue(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(35));
        Assertions.assertFalse(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(36));
        Assertions.assertFalse(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(39));
        Assertions.assertFalse(PlayerInventoryMenuLayout
                .isMainOrHotbarInventorySlot(40));
    }
}
