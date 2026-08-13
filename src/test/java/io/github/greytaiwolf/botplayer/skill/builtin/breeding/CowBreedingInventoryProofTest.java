package io.github.greytaiwolf.botplayer.skill.builtin.breeding;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CowBreedingInventoryProofTest {
    private static final String DIGEST =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final ItemStackFingerprint WHEAT_ONE =
            ItemStackFingerprint.of(
                    new ResourceId("minecraft:wheat"), 1, 0, DIGEST);
    private static final ItemStackFingerprint COBBLESTONE_ONE =
            ItemStackFingerprint.of(
                    new ResourceId("minecraft:cobblestone"), 1, 0, DIGEST);

    @Test
    void acceptsOnlyOneExactDebitFromTheFixedSelectedSlot() {
        InventoryMenuSnapshot before = snapshot(3, 4, ItemStackFingerprint.empty(),
                stack("minecraft:wheat", 2), ItemStackFingerprint.empty());
        CowBreedingInventoryProof proof = CowBreedingInventoryProof.freeze(
                before, 3, WHEAT_ONE);
        InventoryMenuSnapshot after = snapshot(3, 5, ItemStackFingerprint.empty(),
                WHEAT_ONE, ItemStackFingerprint.empty());

        Assertions.assertEquals(
                CowBreedingInventoryProof.Verification.VERIFIED,
                proof.verifyOneDebit(after));
    }

    @Test
    void acceptsEmptyFoodSlotAfterConsumingTheLastWheat() {
        InventoryMenuSnapshot before = snapshot(0, 1, ItemStackFingerprint.empty(),
                WHEAT_ONE, ItemStackFingerprint.empty());
        CowBreedingInventoryProof proof = CowBreedingInventoryProof.freeze(
                before, 0, WHEAT_ONE);
        InventoryMenuSnapshot after = snapshot(0, 2, ItemStackFingerprint.empty(),
                ItemStackFingerprint.empty(), ItemStackFingerprint.empty());

        Assertions.assertTrue(proof.verifyOneDebit(after).verified());
    }

    @Test
    void rejectsCursorSelectionAndUnrelatedInventoryMutation() {
        InventoryMenuSnapshot before = snapshot(2, 4, ItemStackFingerprint.empty(),
                stack("minecraft:wheat", 2), ItemStackFingerprint.empty());
        CowBreedingInventoryProof proof = CowBreedingInventoryProof.freeze(
                before, 2, WHEAT_ONE);

        Assertions.assertEquals(
                CowBreedingInventoryProof.Verification.CURSOR_CHANGED,
                proof.verifyOneDebit(snapshot(2, 5, COBBLESTONE_ONE,
                        WHEAT_ONE, ItemStackFingerprint.empty())));
        Assertions.assertEquals(
                CowBreedingInventoryProof.Verification.SELECTION_CHANGED,
                proof.verifyOneDebit(snapshot(1, 5, ItemStackFingerprint.empty(),
                        WHEAT_ONE, ItemStackFingerprint.empty())));
        Assertions.assertEquals(
                CowBreedingInventoryProof.Verification.UNRELATED_INVENTORY_CHANGED,
                proof.verifyOneDebit(snapshot(2, 5, ItemStackFingerprint.empty(),
                        WHEAT_ONE, COBBLESTONE_ONE)));
        Assertions.assertEquals(
                CowBreedingInventoryProof.Verification.FOOD_DEBIT_MISMATCH,
                proof.verifyOneDebit(snapshot(2, 5, ItemStackFingerprint.empty(),
                        stack("minecraft:wheat", 2), ItemStackFingerprint.empty())));
    }

    @Test
    void refusesANonNativeCursorOrWrongFoodIdentityAtCaptureTime() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CowBreedingInventoryProof.freeze(
                        snapshot(0, 1, COBBLESTONE_ONE,
                                WHEAT_ONE, ItemStackFingerprint.empty()),
                        0,
                        WHEAT_ONE));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> CowBreedingInventoryProof.freeze(
                        snapshot(0, 1, ItemStackFingerprint.empty(),
                                COBBLESTONE_ONE, ItemStackFingerprint.empty()),
                        0,
                        WHEAT_ONE));
    }

    private static InventoryMenuSnapshot snapshot(
            int selected,
            int stateId,
            ItemStackFingerprint cursor,
            ItemStackFingerprint food,
            ItemStackFingerprint extra) {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int slot = 0; slot < 41; slot++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(selected, food);
        if (!extra.isEmpty()) {
            slots.set(12, extra);
        }
        return new InventoryMenuSnapshot(0, stateId, selected, cursor, slots);
    }

    private static ItemStackFingerprint stack(String itemId, int count) {
        return ItemStackFingerprint.of(new ResourceId(itemId), count, 0, DIGEST);
    }
}
