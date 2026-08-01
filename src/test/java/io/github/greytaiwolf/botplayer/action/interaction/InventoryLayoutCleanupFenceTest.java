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
                fence.complete(
                        BOT,
                        4L,
                        FIRST_RUN,
                        InventoryLayoutCleanupResult.RESTORED));
        Assertions.assertEquals(
                InventoryLayoutCleanupResult.RESTORED,
                fence.completedResult(BOT, 4L, lease)
                        .orElseThrow());
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

    @Test
    void blockedAttemptRearmsWithoutConsumingTheLease() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        InventoryLayoutCleanupLease lease = lease(
                FIRST_RUN, 1);
        fence.arm(BOT, 4L, lease);

        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STARTED,
                fence.begin(BOT, 4L, lease));
        Assertions.assertTrue(
                fence.retry(BOT, 4L, FIRST_RUN));
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STARTED,
                fence.begin(BOT, 4L, lease));
        Assertions.assertTrue(
                fence.complete(
                        BOT,
                        4L,
                        FIRST_RUN,
                        InventoryLayoutCleanupResult.ALREADY_SAFE));
        Assertions.assertEquals(
                InventoryLayoutCleanupResult.ALREADY_SAFE,
                fence.completedResult(BOT, 4L, lease)
                        .orElseThrow());
    }

    @Test
    void terminalReceiptRequiresTheExactFrozenLease() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        InventoryLayoutCleanupLease original =
                selectionLease(FIRST_RUN, true);
        InventoryLayoutCleanupLease changed =
                selectionLease(FIRST_RUN, false);
        fence.arm(BOT, 4L, original);
        fence.begin(BOT, 4L, original);
        Assertions.assertTrue(
                fence.complete(
                        BOT,
                        4L,
                        FIRST_RUN,
                        InventoryLayoutCleanupResult.UNSAFE));

        Assertions.assertEquals(
                InventoryLayoutCleanupResult.UNSAFE,
                fence.completedResult(BOT, 4L, original)
                        .orElseThrow());
        Assertions.assertTrue(
                fence.completedResult(BOT, 4L, changed)
                        .isEmpty());
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.StartResult.STALE,
                fence.begin(BOT, 4L, changed));
    }

    @Test
    void terminalReceiptCapacityIsBoundedAndGenerationCloseReclaimsIt() {
        InventoryLayoutCleanupFence fence =
                new InventoryLayoutCleanupFence();
        for (int index = 0;
                index < InventoryLayoutCleanupFence
                        .MAX_CONSUMED_RUNS;
                index++) {
            long generation = index + 1L;
            UUID runId = new UUID(1L, generation);
            InventoryLayoutCleanupLease lease = lease(
                    runId, 1);
            Assertions.assertEquals(
                    InventoryLayoutCleanupFence.ArmResult.ARMED,
                    fence.arm(BOT, generation, lease));
            Assertions.assertTrue(
                    fence.release(BOT, generation, runId));
        }

        InventoryLayoutCleanupLease rejected = lease(
                new UUID(2L, 1L), 1);
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.CAPACITY_EXHAUSTED,
                fence.arm(BOT, 5_000L, rejected));

        fence.closeGeneration(BOT, 1L);
        Assertions.assertEquals(
                InventoryLayoutCleanupFence.ArmResult.ARMED,
                fence.arm(BOT, 5_000L, rejected));
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
