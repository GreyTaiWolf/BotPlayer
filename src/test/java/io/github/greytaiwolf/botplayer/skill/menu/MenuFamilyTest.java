package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MenuFamilyTest {
    @Test
    void acceptsOnlyTheFourExactVanillaShapes() {
        Assertions.assertEquals(46, MenuFamily.INVENTORY_2X2.slotCount());
        Assertions.assertEquals(46, MenuFamily.CRAFTING_3X3.slotCount());
        Assertions.assertEquals(39, MenuFamily.FURNACE.slotCount());
        Assertions.assertEquals(63, MenuFamily.CHEST_3X9.slotCount());
        Assertions.assertEquals(
                MenuSlotRole.RESULT,
                MenuFamily.INVENTORY_2X2.roleAt(0));
        Assertions.assertEquals(
                MenuSlotRole.CRAFTING_INPUT,
                MenuFamily.CRAFTING_3X3.roleAt(9));
        Assertions.assertEquals(
                MenuSlotRole.FURNACE_FUEL,
                MenuFamily.FURNACE.roleAt(1));
        Assertions.assertEquals(
                MenuSlotRole.CONTAINER,
                MenuFamily.CHEST_3X9.roleAt(26));
        Assertions.assertEquals(
                MenuSlotRole.PLAYER_MAIN,
                MenuFamily.CHEST_3X9.roleAt(27));
        Assertions.assertEquals(
                MenuSlotRole.PLAYER_HOTBAR,
                MenuFamily.CHEST_3X9.roleAt(54));

        Assertions.assertEquals(
                MenuFamily.CHEST_3X9,
                MenuFamily.resolveExact("chest_3x9", 63).orElseThrow());
        Assertions.assertTrue(
                MenuFamily.resolveExact("chest_3x9", 62).isEmpty());
        Assertions.assertTrue(
                MenuFamily.resolveExact("modded_chest", 63).isEmpty());
        Assertions.assertTrue(MenuFamily.resolveExact(null, 63).isEmpty());
    }

    @Test
    void rejectsAClosestLookingButWrongSlotCount() {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int index = 0; index < 62; index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MenuSnapshot(
                        MenuFamily.CHEST_3X9,
                        4,
                        8,
                        ItemStackFingerprint.empty(),
                        slots));
    }
}
