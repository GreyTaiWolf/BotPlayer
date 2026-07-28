package io.github.greytaiwolf.botplayer.inventory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class BotInventoryLayoutTest {
    private static final int SLOT_SIZE = 16;

    @Test
    void rangesAreContiguousAndContainExactlySeventySevenSlots() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(0, BotInventoryLayout.BOT_ARMOR_START),
                () -> Assertions.assertEquals(
                        BotInventoryLayout.BOT_ARMOR_END,
                        BotInventoryLayout.BOT_OFFHAND_SLOT),
                () -> Assertions.assertEquals(
                        BotInventoryLayout.BOT_OFFHAND_SLOT + 1,
                        BotInventoryLayout.BOT_MAIN_START),
                () -> Assertions.assertEquals(
                        BotInventoryLayout.BOT_MAIN_END,
                        BotInventoryLayout.BOT_HOTBAR_START),
                () -> Assertions.assertEquals(
                        BotInventoryLayout.BOT_HOTBAR_END,
                        BotInventoryLayout.VIEWER_MAIN_START),
                () -> Assertions.assertEquals(
                        BotInventoryLayout.VIEWER_MAIN_END,
                        BotInventoryLayout.VIEWER_HOTBAR_START),
                () -> Assertions.assertEquals(
                        BotInventoryLayout.VIEWER_HOTBAR_END,
                        BotInventoryLayout.TOTAL_MENU_SLOTS),
                () -> Assertions.assertEquals(
                        41, BotInventoryLayout.BOT_INVENTORY_SIZE),
                () -> Assertions.assertEquals(
                        36, BotInventoryLayout.VIEWER_INVENTORY_SIZE),
                () -> Assertions.assertEquals(
                        77, BotInventoryLayout.TOTAL_MENU_SLOTS));
    }

    @Test
    void botMenuMapsEveryRealInventoryIndexExactlyOnce() {
        HashSet<Integer> inventoryIndexes = new HashSet<>();

        for (int menuIndex = 0;
                menuIndex < BotInventoryLayout.BOT_INVENTORY_SIZE;
                menuIndex++) {
            inventoryIndexes.add(
                    BotInventoryLayout.botInventoryIndex(menuIndex));
        }

        Assertions.assertEquals(
                BotInventoryLayout.BOT_INVENTORY_SIZE,
                inventoryIndexes.size());
        for (int inventoryIndex = 0;
                inventoryIndex < BotInventoryLayout.BOT_INVENTORY_SIZE;
                inventoryIndex++) {
            Assertions.assertTrue(
                    inventoryIndexes.contains(inventoryIndex),
                    "Missing bot inventory index " + inventoryIndex);
        }

        Assertions.assertAll(
                () -> Assertions.assertEquals(
                        39, BotInventoryLayout.botInventoryIndex(0)),
                () -> Assertions.assertEquals(
                        36, BotInventoryLayout.botInventoryIndex(3)),
                () -> Assertions.assertEquals(
                        40, BotInventoryLayout.botInventoryIndex(4)),
                () -> Assertions.assertEquals(
                        9, BotInventoryLayout.botInventoryIndex(5)),
                () -> Assertions.assertEquals(
                        35, BotInventoryLayout.botInventoryIndex(31)),
                () -> Assertions.assertEquals(
                        0, BotInventoryLayout.botInventoryIndex(32)),
                () -> Assertions.assertEquals(
                        8, BotInventoryLayout.botInventoryIndex(40)));
    }

    @Test
    void viewerMenuMapsEveryViewerInventoryIndexExactlyOnce() {
        HashSet<Integer> inventoryIndexes = new HashSet<>();

        for (int menuIndex = BotInventoryLayout.VIEWER_MAIN_START;
                menuIndex < BotInventoryLayout.TOTAL_MENU_SLOTS;
                menuIndex++) {
            inventoryIndexes.add(
                    BotInventoryLayout.viewerInventoryIndex(menuIndex));
        }

        Assertions.assertEquals(
                BotInventoryLayout.VIEWER_INVENTORY_SIZE,
                inventoryIndexes.size());
        for (int inventoryIndex = 0;
                inventoryIndex < BotInventoryLayout.VIEWER_INVENTORY_SIZE;
                inventoryIndex++) {
            Assertions.assertTrue(
                    inventoryIndexes.contains(inventoryIndex),
                    "Missing viewer inventory index " + inventoryIndex);
        }
    }

    @Test
    void mappingRejectsTheOtherOwnerAndOutOfRangeSlots() {
        Assertions.assertAll(
                () -> Assertions.assertThrows(
                        IndexOutOfBoundsException.class,
                        () -> BotInventoryLayout.botInventoryIndex(-1)),
                () -> Assertions.assertThrows(
                        IndexOutOfBoundsException.class,
                        () -> BotInventoryLayout.botInventoryIndex(
                                BotInventoryLayout.BOT_INVENTORY_SIZE)),
                () -> Assertions.assertThrows(
                        IndexOutOfBoundsException.class,
                        () -> BotInventoryLayout.viewerInventoryIndex(
                                BotInventoryLayout.VIEWER_MAIN_START - 1)),
                () -> Assertions.assertThrows(
                        IndexOutOfBoundsException.class,
                        () -> BotInventoryLayout.viewerInventoryIndex(
                                BotInventoryLayout.TOTAL_MENU_SLOTS)));
    }

    @Test
    void coordinatesMatchTheVanillaPlayerInventoryArrangement() {
        Assertions.assertAll(
                () -> Assertions.assertEquals(176, BotInventoryLayout.IMAGE_WIDTH),
                () -> Assertions.assertEquals(256, BotInventoryLayout.IMAGE_HEIGHT),
                () -> assertCoordinate(
                        BotInventoryLayout.BOT_EQUIPMENT_X,
                        BotInventoryLayout.BOT_EQUIPMENT_Y,
                        8,
                        8),
                () -> assertCoordinate(
                        BotInventoryLayout.BOT_EQUIPMENT_X,
                        BotInventoryLayout.BOT_EQUIPMENT_Y
                                + 3 * BotInventoryLayout.SLOT_SPACING,
                        8,
                        62),
                () -> assertCoordinate(
                        BotInventoryLayout.BOT_OFFHAND_X,
                        BotInventoryLayout.BOT_OFFHAND_Y,
                        77,
                        62),
                () -> assertCoordinate(
                        BotInventoryLayout.BOT_MAIN_X,
                        BotInventoryLayout.BOT_MAIN_Y,
                        8,
                        84),
                () -> assertCoordinate(
                        BotInventoryLayout.BOT_MAIN_X
                                + 8 * BotInventoryLayout.SLOT_SPACING,
                        BotInventoryLayout.BOT_MAIN_Y
                                + 2 * BotInventoryLayout.SLOT_SPACING,
                        152,
                        120),
                () -> assertCoordinate(
                        BotInventoryLayout.BOT_HOTBAR_X,
                        BotInventoryLayout.BOT_HOTBAR_Y,
                        8,
                        142),
                () -> assertCoordinate(
                        BotInventoryLayout.VIEWER_MAIN_X,
                        BotInventoryLayout.VIEWER_MAIN_Y,
                        8,
                        174),
                () -> assertCoordinate(
                        BotInventoryLayout.VIEWER_HOTBAR_X
                                + 8 * BotInventoryLayout.SLOT_SPACING,
                        BotInventoryLayout.VIEWER_HOTBAR_Y,
                        152,
                        232));
    }

    @Test
    void allSeventySevenSlotsStayInsideTheScreenAndDoNotOverlap() {
        List<SlotCoordinate> coordinates = allSlotCoordinates();

        Assertions.assertEquals(
                BotInventoryLayout.TOTAL_MENU_SLOTS, coordinates.size());
        Assertions.assertEquals(
                BotInventoryLayout.TOTAL_MENU_SLOTS,
                new HashSet<>(coordinates).size(),
                "Menu slots overlap");

        for (int menuIndex = 0; menuIndex < coordinates.size(); menuIndex++) {
            SlotCoordinate coordinate = coordinates.get(menuIndex);
            Assertions.assertTrue(
                    coordinate.x() >= 0
                            && coordinate.x() + SLOT_SIZE
                                    <= BotInventoryLayout.IMAGE_WIDTH,
                    "Menu slot "
                            + menuIndex
                            + " exceeds the horizontal screen bounds at "
                            + coordinate);
            Assertions.assertTrue(
                    coordinate.y() >= 0
                            && coordinate.y() + SLOT_SIZE
                                    <= BotInventoryLayout.IMAGE_HEIGHT,
                    "Menu slot "
                            + menuIndex
                            + " exceeds the vertical screen bounds at "
                            + coordinate);
        }
    }

    private static List<SlotCoordinate> allSlotCoordinates() {
        List<SlotCoordinate> coordinates =
                new ArrayList<>(BotInventoryLayout.TOTAL_MENU_SLOTS);

        for (int armorIndex = 0; armorIndex < 4; armorIndex++) {
            coordinates.add(new SlotCoordinate(
                    BotInventoryLayout.BOT_EQUIPMENT_X,
                    BotInventoryLayout.BOT_EQUIPMENT_Y
                            + armorIndex * BotInventoryLayout.SLOT_SPACING));
        }
        coordinates.add(new SlotCoordinate(
                BotInventoryLayout.BOT_OFFHAND_X,
                BotInventoryLayout.BOT_OFFHAND_Y));
        addGrid(
                coordinates,
                BotInventoryLayout.BOT_MAIN_X,
                BotInventoryLayout.BOT_MAIN_Y,
                3);
        addGrid(
                coordinates,
                BotInventoryLayout.BOT_HOTBAR_X,
                BotInventoryLayout.BOT_HOTBAR_Y,
                1);
        addGrid(
                coordinates,
                BotInventoryLayout.VIEWER_MAIN_X,
                BotInventoryLayout.VIEWER_MAIN_Y,
                3);
        addGrid(
                coordinates,
                BotInventoryLayout.VIEWER_HOTBAR_X,
                BotInventoryLayout.VIEWER_HOTBAR_Y,
                1);
        return coordinates;
    }

    private static void addGrid(
            List<SlotCoordinate> coordinates, int startX, int startY, int rows) {
        for (int row = 0; row < rows; row++) {
            for (int column = 0; column < 9; column++) {
                coordinates.add(new SlotCoordinate(
                        startX + column * BotInventoryLayout.SLOT_SPACING,
                        startY + row * BotInventoryLayout.SLOT_SPACING));
            }
        }
    }

    private static void assertCoordinate(
            int actualX, int actualY, int expectedX, int expectedY) {
        Assertions.assertEquals(
                new SlotCoordinate(expectedX, expectedY),
                new SlotCoordinate(actualX, actualY));
    }

    private record SlotCoordinate(int x, int y) {}
}
