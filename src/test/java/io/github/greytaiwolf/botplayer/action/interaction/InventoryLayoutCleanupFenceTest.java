package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryLayoutCleanupFenceTest {
    private static final String COMPONENTS =
            "0000000000000000000000000000000000000000000000000000000000000000";
    private static final UUID BOT = new UUID(0L, 1L);
    private static final UUID FIRST_RUN = new UUID(0L, 2L);
    private static final UUID SECOND_RUN = new UUID(0L, 3L);
    private static final ItemStackFingerprint FOOD =
            ItemStackFingerprint.of(
                    new ResourceId("minecraft:melon_slice"),
                    2,
                    0,
                    COMPONENTS);

    @Test
    void exactRunMayMutateOnlyOnce() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        InventoryLayoutCleanupLease lease = lease(
                FIRST_RUN, 1);

        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.ARMED,
                fence.arm(BOT, 4L, lease));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STARTED,
                fence.begin(BOT, 4L, lease));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.BUSY,
                fence.begin(BOT, 4L, lease));
        Assertions.assertTrue(
                fence.complete(BOT, 4L, FIRST_RUN));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STALE,
                fence.begin(BOT, 4L, lease));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.CONFLICT,
                fence.arm(BOT, 4L, lease));
    }

    @Test
    void staleRunCannotCrossAReplacementLease() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        InventoryLayoutCleanupLease first = lease(
                FIRST_RUN, 1);
        InventoryLayoutCleanupLease replacement = lease(
                SECOND_RUN, 1);
        fence.arm(BOT, 4L, first);

        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.CONFLICT,
                fence.arm(BOT, 4L, replacement));
        Assertions.assertTrue(
                fence.release(BOT, 4L, FIRST_RUN));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.ARMED,
                fence.arm(BOT, 4L, replacement));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STALE,
                fence.begin(BOT, 4L, first));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STARTED,
                fence.begin(BOT, 4L, replacement));
    }

    @Test
    void executingLeaseCannotBeReleasedOrRearmed() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        InventoryLayoutCleanupLease first = lease(
                FIRST_RUN, 1);
        InventoryLayoutCleanupLease replacement = lease(
                SECOND_RUN, 1);
        fence.arm(BOT, 4L, first);
        fence.begin(BOT, 4L, first);

        Assertions.assertFalse(
                fence.release(BOT, 4L, FIRST_RUN));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.CONFLICT,
                fence.arm(BOT, 4L, replacement));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.BUSY,
                fence.begin(BOT, 4L, first));
    }

    @Test
    void changedPayloadCannotUseAnArmedRunIdentity() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        InventoryLayoutCleanupLease armed =
                selectionLease(
                        FIRST_RUN, false);
        InventoryLayoutCleanupLease changed =
                selectionLease(
                        FIRST_RUN, true);
        fence.arm(BOT, 4L, armed);

        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STALE,
                fence.begin(BOT, 4L, changed));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STARTED,
                fence.begin(BOT, 4L, armed));
    }

    private static InventoryLayoutCleanupLease lease(
            UUID runId, int temporarySlot) {
        return new InventoryLayoutCleanupLease(
                runId,
                9,
                temporarySlot,
                FOOD,
                new InventoryContentsSnapshot(
                        java.util.List.of(FOOD)),
                0,
                true,
                true);
    }

    private static InventoryLayoutCleanupLease
            selectionLease(
                    UUID runId,
                    boolean checkSelection) {
        return new InventoryLayoutCleanupLease(
                runId,
                -1,
                1,
                FOOD,
                new InventoryContentsSnapshot(
                        java.util.List.of(FOOD)),
                0,
                false,
                checkSelection);
    }
}
