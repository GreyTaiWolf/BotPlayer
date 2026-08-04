package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryLayoutCleanupPolicyTest {
    private static final String COMPONENTS =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final ItemStackFingerprint EMPTY =
            ItemStackFingerprint.empty();
    private static final ItemStackFingerprint FOOD =
            item("minecraft:melon_slice", 2);
    private static final ItemStackFingerprint FOOD_ONE =
            item("minecraft:melon_slice", 1);
    private static final ItemStackFingerprint FOOD_THREE =
            item("minecraft:melon_slice", 3);
    private static final ItemStackFingerprint DIAMOND =
            item("minecraft:diamond", 1);
    private static final ItemStackFingerprint DIAMOND_TWO =
            item("minecraft:diamond", 2);
    private static final ItemStackFingerprint COBBLESTONE =
            item("minecraft:cobblestone", 4);
    private static final ItemStackFingerprint DIRT =
            item("minecraft:dirt", 1);

    @Test
    void distinguishesRestoredAndTemporaryLayouts() {
        InventoryLayoutCleanupRequest request = request(
                FOOD,
                snapshot(
                        FOOD,
                        DIAMOND,
                        COBBLESTONE));

        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision
                        .ALREADY_SAFE,
                assess(
                                request,
                                FOOD,
                                EMPTY,
                                snapshot(
                                        FOOD,
                                        DIAMOND,
                                        COBBLESTONE))
                        .decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision
                        .TEMPORARY_LAYOUT,
                assess(
                                request,
                                EMPTY,
                                FOOD,
                                snapshot(
                                        FOOD,
                                        DIAMOND,
                                        COBBLESTONE))
                        .decision());
    }

    @Test
    void acceptsExactlyOneConsumptionAndLastItemExhaustion() {
        InventoryLayoutCleanupRequest twoFood = request(
                FOOD,
                snapshot(FOOD, DIAMOND));
        InventoryLayoutCleanupPolicy.Assessment oneConsumed =
                assess(
                        twoFood,
                        EMPTY,
                        FOOD_ONE,
                        snapshot(
                                FOOD_ONE,
                                DIAMOND));
        InventoryLayoutCleanupRequest lastFood = request(
                FOOD_ONE,
                snapshot(FOOD_ONE, DIAMOND));
        InventoryLayoutCleanupPolicy.Assessment exhausted =
                assess(
                        lastFood,
                        EMPTY,
                        EMPTY,
                        snapshot(DIAMOND));

        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision
                        .TEMPORARY_LAYOUT,
                oneConsumed.decision());
        Assertions.assertEquals(
                1, oneConsumed.expectedRemainderCount());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision
                        .ALREADY_SAFE,
                exhausted.decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                lastFood,
                                EMPTY,
                                DIAMOND,
                                snapshot(DIAMOND))
                        .decision());
    }

    @Test
    void commitsOnlyAnExistingNonTargetMovedIntoSource() {
        InventoryLayoutCleanupRequest request = request(
                FOOD,
                snapshot(
                        FOOD,
                        DIAMOND,
                        COBBLESTONE));

        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision
                        .SAFE_LAYOUT_COMMITTED,
                assess(
                                request,
                                DIAMOND,
                                FOOD,
                                snapshot(
                                        FOOD,
                                        DIAMOND,
                                        COBBLESTONE))
                        .decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                DIRT,
                                FOOD,
                                snapshot(
                                        FOOD,
                                        DIAMOND,
                                        COBBLESTONE,
                                        DIRT))
                        .decision());
    }

    @Test
    void rejectsNonTargetLossDuplicationAndReplacement() {
        InventoryLayoutCleanupRequest request = request(
                FOOD,
                snapshot(
                        FOOD,
                        DIAMOND,
                        COBBLESTONE));

        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                EMPTY,
                                FOOD,
                                snapshot(
                                        FOOD,
                                        COBBLESTONE))
                        .decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                EMPTY,
                                FOOD,
                                snapshot(
                                        FOOD,
                                        DIAMOND_TWO,
                                        COBBLESTONE))
                        .decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                EMPTY,
                                FOOD,
                                snapshot(
                                        FOOD,
                                        DIAMOND,
                                        DIRT))
                        .decision());
    }

    @Test
    void rejectsTargetDuplicationAndMoreThanOneLoss() {
        InventoryLayoutCleanupRequest request = request(
                FOOD,
                snapshot(FOOD, DIAMOND));

        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                EMPTY,
                                FOOD_THREE,
                                snapshot(
                                        FOOD_THREE,
                                        DIAMOND))
                        .decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                EMPTY,
                                EMPTY,
                                snapshot(DIAMOND))
                        .decision());
    }

    @Test
    void rejectsSplitRemaindersAndAnOccupiedTemporarySlot() {
        InventoryLayoutCleanupRequest request = request(
                FOOD,
                snapshot(
                        FOOD,
                        DIAMOND));

        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                FOOD_ONE,
                                FOOD_ONE,
                                snapshot(
                                        FOOD_ONE,
                                        FOOD_ONE,
                                        DIAMOND))
                        .decision());
        Assertions.assertEquals(
                InventoryLayoutCleanupPolicy.Decision.UNSAFE,
                assess(
                                request,
                                FOOD,
                                DIAMOND,
                                snapshot(
                                        FOOD,
                                        DIAMOND))
                        .decision());
    }

    @Test
    void selectionOnlyCleanupStillRequiresGlobalConservation() {
        InventoryContentsSnapshot before = snapshot(
                FOOD,
                DIAMOND);
        InventoryLayoutCleanupRequest request =
                new InventoryLayoutCleanupRequest(
                        new InventoryLayoutCleanupLease(
                                new UUID(0L, 2L),
                                -1,
                                1,
                                FOOD,
                                before,
                                0,
                                false,
                                true));

        Assertions.assertFalse(
                InventoryLayoutCleanupPolicy
                        .conservesInventory(
                                request,
                                snapshot(FOOD)));
        Assertions.assertTrue(
                InventoryLayoutCleanupPolicy
                        .conservesInventory(
                                request,
                                before));
    }

    private static InventoryLayoutCleanupPolicy.Assessment assess(
            InventoryLayoutCleanupRequest request,
            ItemStackFingerprint source,
            ItemStackFingerprint temporary,
            InventoryContentsSnapshot current) {
        return InventoryLayoutCleanupPolicy.assess(
                request,
                source,
                temporary,
                current);
    }

    private static InventoryLayoutCleanupRequest request(
            ItemStackFingerprint food,
            InventoryContentsSnapshot before) {
        return new InventoryLayoutCleanupRequest(
                new InventoryLayoutCleanupLease(
                        new UUID(0L, 1L),
                        9,
                        1,
                        food,
                        before,
                        0,
                        true,
                        true));
    }

    private static InventoryContentsSnapshot snapshot(
            ItemStackFingerprint... stacks) {
        return new InventoryContentsSnapshot(
                List.of(stacks));
    }

    private static ItemStackFingerprint item(
            String id, int count) {
        return ItemStackFingerprint.of(
                new ResourceId(id),
                count,
                0,
                COMPONENTS);
    }
}
