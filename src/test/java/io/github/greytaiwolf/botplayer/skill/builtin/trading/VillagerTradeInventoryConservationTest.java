package io.github.greytaiwolf.botplayer.skill.builtin.trading;

import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class VillagerTradeInventoryConservationTest {
    private static final ItemStackFingerprint COST =
            stack("minecraft:wheat", 5, 'a');
    private static final ItemStackFingerprint RESULT =
            stack("minecraft:emerald", 1, 'b');

    @Test
    void acceptsExactlyOneCostDecreaseAndOneResultIncrease() {
        InventoryContentsSnapshot before = inventory(
                stack("minecraft:wheat", 7, 'a'),
                stack("minecraft:cobblestone", 64, 'c'));
        InventoryContentsSnapshot after = inventory(
                stack("minecraft:wheat", 2, 'a'),
                RESULT,
                stack("minecraft:cobblestone", 64, 'c'));

        Assertions.assertAll(
                () -> Assertions.assertTrue(
                        VillagerTradeInventoryConservation.matchesCompletedTrade(
                                before, after, COST, RESULT)),
                () -> Assertions.assertEquals(
                        VillagerTradeInventoryConservation.CancellationSettlement
                                .COMPLETED,
                        VillagerTradeInventoryConservation
                                .cancellationSettlement(
                                        before, after, COST, RESULT)));
    }

    @Test
    void rejectsUnrelatedOrPartialInventoryDriftAndClassifiesUnchangedCancel() {
        InventoryContentsSnapshot before = inventory(
                stack("minecraft:wheat", 7, 'a'),
                stack("minecraft:cobblestone", 64, 'c'));
        InventoryContentsSnapshot unrelatedDrift = inventory(
                stack("minecraft:wheat", 2, 'a'),
                RESULT,
                stack("minecraft:cobblestone", 63, 'c'));
        InventoryContentsSnapshot partialPayment = inventory(
                stack("minecraft:wheat", 5, 'a'),
                stack("minecraft:cobblestone", 64, 'c'));

        Assertions.assertAll(
                () -> Assertions.assertFalse(
                        VillagerTradeInventoryConservation.matchesCompletedTrade(
                                before, unrelatedDrift, COST, RESULT)),
                () -> Assertions.assertFalse(
                        VillagerTradeInventoryConservation.matchesCompletedTrade(
                                before, partialPayment, COST, RESULT)),
                () -> Assertions.assertEquals(
                        VillagerTradeInventoryConservation.CancellationSettlement
                                .UNCHANGED,
                        VillagerTradeInventoryConservation
                                .cancellationSettlement(
                                        before, before, COST, RESULT)),
                () -> Assertions.assertEquals(
                        VillagerTradeInventoryConservation.CancellationSettlement
                                .UNSAFE,
                        VillagerTradeInventoryConservation
                                .cancellationSettlement(
                                        before, partialPayment, COST, RESULT)));
    }

    private static InventoryContentsSnapshot inventory(
            ItemStackFingerprint... items) {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (ItemStackFingerprint item : items) {
            slots.add(item);
        }
        return new InventoryContentsSnapshot(slots);
    }

    private static ItemStackFingerprint stack(
            String itemId, int count, char digest) {
        return ItemStackFingerprint.of(new ResourceId(itemId), count, 0,
                String.valueOf(digest).repeat(64));
    }
}
