package io.github.greytaiwolf.botplayer.skill.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class MenuTransactionTest {
    @Test
    void strictConservationIncludesCursorAndCountsRatherThanSlotDigest() {
        MenuSnapshot before = chest(7, 10, List.of(
                SlotChange.at(0, item("oak_log", 3, '1'))));
        MenuSnapshot after = chest(7, 11, List.of(
                SlotChange.at(27, item("oak_log", 3, '1'))));

        MenuClickStep step = new MenuClickStep(
                new MenuClick(0, MenuClickType.QUICK_MOVE, 0),
                before,
                after,
                MenuConservationRule.strict());
        Assertions.assertEquals(
                MenuConservationRule.strict(), step.conservation());
        Assertions.assertTrue(
                MenuConservationRule.delta(before, after).isEmpty());

        MenuSnapshot duplicated = chest(7, 11, List.of(
                SlotChange.at(27, item("oak_log", 4, '1'))));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MenuClickStep(
                        new MenuClick(0, MenuClickType.QUICK_MOVE, 0),
                        before,
                        duplicated,
                        MenuConservationRule.strict()));

        MenuSnapshot cursorLeak = new MenuSnapshot(
                MenuFamily.CHEST_3X9,
                7,
                11,
                item("oak_log", 1, '1'),
                after.slots());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MenuClickStep(
                        new MenuClick(0, MenuClickType.QUICK_MOVE, 0),
                        before,
                        cursorLeak,
                        MenuConservationRule.strict()));
    }

    @Test
    void recipeDeltaMustBeExplicitAndExact() {
        MenuSnapshot before = crafting(9, 30, List.of(
                SlotChange.at(1, item("oak_log", 1, '1'))));
        MenuSnapshot after = crafting(9, 31, List.of(
                SlotChange.at(0, item("oak_planks", 4, '2'))));
        MenuItemKey log = key("oak_log", '1');
        MenuItemKey planks = key("oak_planks", '2');
        MenuConservationRule recipe = new MenuConservationRule(Map.of(
                log, -1L,
                planks, 4L));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MenuClickStep(
                        new MenuClick(0, MenuClickType.PICKUP, 0),
                        before,
                        after,
                        MenuConservationRule.strict()));
        Assertions.assertDoesNotThrow(
                () -> new MenuClickStep(
                        new MenuClick(0, MenuClickType.PICKUP, 0),
                        before,
                        after,
                        recipe));
    }

    @Test
    void emitsAtMostOneClickPerTickAndRequiresFreshAck() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1')),
                SlotChange.at(1, item("cobblestone", 1, '2'))));
        MenuSnapshot first = chest(3, 0, List.of(
                SlotChange.at(1, item("cobblestone", 1, '2')),
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuSnapshot second = chest(3, 0, List.of(
                SlotChange.at(27, item("oak_log", 1, '1')),
                SlotChange.at(28, item("cobblestone", 1, '2'))));
        MenuTransactionPlan plan = new MenuTransactionPlan(
                MenuFamily.CHEST_3X9,
                opened,
                List.of(
                        new MenuClickStep(
                                new MenuClick(
                                        0,
                                        MenuClickType.QUICK_MOVE,
                                        0),
                                opened,
                                first,
                                MenuConservationRule.strict()),
                        new MenuClickStep(
                                new MenuClick(
                                        1,
                                        MenuClickType.QUICK_MOVE,
                                        0),
                                first,
                                second,
                                MenuConservationRule.strict())),
                second);

        MenuTransaction transaction = MenuTransaction.opening(
                MenuFamily.CHEST_3X9,
                new MenuTransactionLimits(2, 20L),
                10L);
        Assertions.assertTrue(transaction.observeOpened(opened, 10L));
        Assertions.assertEquals(opened, transaction.beginPlanning(10L));
        Assertions.assertTrue(transaction.installPlan(plan, 10L));

        Assertions.assertEquals(
                new MenuClick(0, MenuClickType.QUICK_MOVE, 0),
                transaction.issueNextClick(11L, opened).orElseThrow());
        Assertions.assertTrue(transaction.acknowledge(
                withState(first, 21), 11L));
        Assertions.assertTrue(
                transaction.issueNextClick(11L, withState(first, 21))
                        .isEmpty());
        Assertions.assertEquals(
                new MenuClick(1, MenuClickType.QUICK_MOVE, 0),
                transaction.issueNextClick(12L, withState(first, 21))
                        .orElseThrow());
        Assertions.assertTrue(transaction.acknowledge(
                withState(second, 22), 12L));
        Assertions.assertEquals(
                MenuTransactionState.VERIFYING, transaction.state());
        Assertions.assertTrue(transaction.verify(withState(second, 22), 13L));
        Assertions.assertTrue(transaction.closeConfirmed(13L));
        Assertions.assertEquals(
                MenuTransactionState.COMPLETED, transaction.state());
    }

    @Test
    void failsClosedForStaleAckAndUnexpectedPreClickLayout() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1'))));
        MenuSnapshot moved = chest(3, 0, List.of(
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuTransactionPlan plan = oneStepPlan(opened, moved);

        MenuTransaction staleAck = start(plan, opened);
        Assertions.assertTrue(staleAck.issueNextClick(11L, opened).isPresent());
        Assertions.assertFalse(staleAck.acknowledge(moved, 11L));
        Assertions.assertEquals(
                MenuTransactionFailure.STALE_STATE,
                staleAck.failure().orElseThrow());

        MenuTransaction drift = start(plan, opened);
        MenuSnapshot changedBeforeClick = chest(3, 21, List.of(
                SlotChange.at(0, item("oak_log", 2, '1'))));
        Assertions.assertTrue(
                drift.issueNextClick(11L, changedBeforeClick).isEmpty());
        Assertions.assertEquals(
                MenuTransactionFailure.SNAPSHOT_DRIFT,
                drift.failure().orElseThrow());
    }

    @Test
    void clickDispatchExceptionFailsClosedBeforeOrAfterMutationWithoutAckOrRetry() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1'))));
        MenuSnapshot moved = chest(3, 21, List.of(
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuTransactionPlan plan = oneStepPlan(
                opened, withState(moved, 0));

        MenuTransaction beforeMutation = start(plan, opened);
        Assertions.assertTrue(beforeMutation.issueNextClick(
                11L, opened).isPresent());
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> beforeMutation.issueNextClick(11L, opened));
        Assertions.assertFalse(beforeMutation
                .failAfterClickDispatchException(opened, 11L));
        Assertions.assertEquals(
                MenuTransactionState.FAILED, beforeMutation.state());
        Assertions.assertEquals(
                MenuTransactionFailure.CLICK_DISPATCH_FAILED,
                beforeMutation.failure().orElseThrow());
        Assertions.assertEquals(0, beforeMutation.confirmedClicks());
        Assertions.assertEquals(opened,
                beforeMutation.observedSnapshot().orElseThrow());
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> beforeMutation.acknowledge(moved, 11L));
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> beforeMutation.issueNextClick(12L, opened));

        MenuTransaction afterMutation = start(plan, opened);
        Assertions.assertTrue(afterMutation.issueNextClick(
                11L, opened).isPresent());
        Assertions.assertFalse(afterMutation
                .failAfterClickDispatchException(moved, 11L));
        Assertions.assertEquals(
                MenuTransactionState.FAILED, afterMutation.state());
        Assertions.assertEquals(
                MenuTransactionFailure.CLICK_DISPATCH_FAILED,
                afterMutation.failure().orElseThrow());
        Assertions.assertEquals(0, afterMutation.confirmedClicks());
        Assertions.assertEquals(moved,
                afterMutation.observedSnapshot().orElseThrow());
        Assertions.assertThrows(
                IllegalStateException.class,
                () -> afterMutation.acknowledge(moved, 11L));
    }

    @Test
    void clickDispatchExceptionNeverAcceptsForeignOrLateObservation() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1'))));
        MenuSnapshot moved = chest(3, 21, List.of(
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuTransactionPlan plan = oneStepPlan(
                opened, withState(moved, 0));

        MenuTransaction foreign = start(plan, opened);
        Assertions.assertTrue(foreign.issueNextClick(
                11L, opened).isPresent());
        Assertions.assertFalse(foreign.failAfterClickDispatchException(
                chest(4, 21, List.of()), 11L));
        Assertions.assertEquals(opened,
                foreign.observedSnapshot().orElseThrow());
        Assertions.assertEquals(0, foreign.confirmedClicks());

        MenuTransaction late = start(plan, opened);
        Assertions.assertTrue(late.issueNextClick(11L, opened).isPresent());
        Assertions.assertFalse(late.failAfterClickDispatchException(
                moved, 12L));
        Assertions.assertEquals(opened,
                late.observedSnapshot().orElseThrow());
        Assertions.assertEquals(0, late.confirmedClicks());
    }

    @Test
    void clickDispatchBoundaryRereadsExactlyOnceBeforeOrAfterMutation() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1'))));
        MenuSnapshot moved = chest(3, 21, List.of(
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuTransactionPlan plan = oneStepPlan(
                opened, withState(moved, 0));

        MenuTransaction beforeMutation = start(plan, opened);
        Assertions.assertTrue(beforeMutation.issueNextClick(
                11L, opened).isPresent());
        AtomicInteger beforeDispatches = new AtomicInteger();
        AtomicInteger beforeReads = new AtomicInteger();
        MenuClickDispatchBoundary.Result before =
                MenuClickDispatchBoundary.dispatch(
                        beforeMutation,
                        11L,
                        () -> {
                            beforeDispatches.incrementAndGet();
                            throw new IllegalStateException("before mutation");
                        },
                        () -> {
                            beforeReads.incrementAndGet();
                            return opened;
                        });
        Assertions.assertEquals(
                MenuClickDispatchBoundary.Disposition.FAILED,
                before.disposition());
        Assertions.assertEquals(1, beforeDispatches.get());
        Assertions.assertEquals(1, beforeReads.get());
        Assertions.assertEquals(opened,
                before.observedAfter().orElseThrow());
        Assertions.assertEquals(0, beforeMutation.confirmedClicks());
        Assertions.assertEquals(
                MenuTransactionFailure.CLICK_DISPATCH_FAILED,
                beforeMutation.failure().orElseThrow());

        MenuTransaction afterMutation = start(plan, opened);
        Assertions.assertTrue(afterMutation.issueNextClick(
                11L, opened).isPresent());
        AtomicReference<MenuSnapshot> observed =
                new AtomicReference<>(opened);
        AtomicInteger afterReads = new AtomicInteger();
        MenuClickDispatchBoundary.Result after =
                MenuClickDispatchBoundary.dispatch(
                        afterMutation,
                        11L,
                        () -> {
                            observed.set(moved);
                            throw new IllegalStateException("after mutation");
                        },
                        () -> {
                            afterReads.incrementAndGet();
                            return observed.get();
                        });
        Assertions.assertEquals(
                MenuClickDispatchBoundary.Disposition.FAILED,
                after.disposition());
        Assertions.assertEquals(1, afterReads.get());
        Assertions.assertEquals(moved,
                after.observedAfter().orElseThrow());
        Assertions.assertEquals(moved,
                afterMutation.observedSnapshot().orElseThrow());
        Assertions.assertEquals(0, afterMutation.confirmedClicks());
        Assertions.assertEquals(
                MenuTransactionFailure.CLICK_DISPATCH_FAILED,
                afterMutation.failure().orElseThrow());
    }

    @Test
    void clickDispatchBoundaryFailsClosedForRereadOrReentrantCancellation() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1'))));
        MenuSnapshot moved = chest(3, 21, List.of(
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuTransactionPlan plan = oneStepPlan(
                opened, withState(moved, 0));

        MenuTransaction rereadFailure = start(plan, opened);
        Assertions.assertTrue(rereadFailure.issueNextClick(
                11L, opened).isPresent());
        AtomicInteger reads = new AtomicInteger();
        MenuClickDispatchBoundary.Result failedReread =
                MenuClickDispatchBoundary.dispatch(
                        rereadFailure,
                        11L,
                        () -> {
                        },
                        () -> {
                            reads.incrementAndGet();
                            throw new IllegalStateException("reread failed");
                        });
        Assertions.assertEquals(
                MenuClickDispatchBoundary.Disposition.FAILED,
                failedReread.disposition());
        Assertions.assertTrue(failedReread.observedAfter().isEmpty());
        Assertions.assertEquals(1, reads.get());
        Assertions.assertEquals(
                MenuTransactionFailure.CLICK_DISPATCH_FAILED,
                rereadFailure.failure().orElseThrow());

        MenuTransaction reentrantCancellation = start(plan, opened);
        Assertions.assertTrue(reentrantCancellation.issueNextClick(
                11L, opened).isPresent());
        MenuClickDispatchBoundary.Result reentrant =
                MenuClickDispatchBoundary.dispatch(
                        reentrantCancellation,
                        11L,
                        () -> {
                            Assertions.assertTrue(reentrantCancellation.cancel());
                            throw new IllegalStateException("reentrant cancel");
                        },
                        () -> opened);
        Assertions.assertEquals(
                MenuClickDispatchBoundary.Disposition.UNSAFE_REENTRANT,
                reentrant.disposition());
        Assertions.assertEquals(
                MenuTransactionState.CANCELLED,
                reentrantCancellation.state());
        Assertions.assertEquals(
                MenuTransactionFailure.CANCELLED,
                reentrantCancellation.failure().orElseThrow());
        Assertions.assertEquals(0, reentrantCancellation.confirmedClicks());

        MenuTransaction normal = start(plan, opened);
        Assertions.assertTrue(normal.issueNextClick(11L, opened).isPresent());
        MenuClickDispatchBoundary.Result normalResult =
                MenuClickDispatchBoundary.dispatch(
                        normal, 11L, () -> {
                        }, () -> moved);
        Assertions.assertTrue(normalResult.mayAcknowledge());
        Assertions.assertEquals(
                MenuClickDispatchBoundary.Disposition.ACKNOWLEDGE,
                normalResult.disposition());
        Assertions.assertTrue(normal.acknowledge(
                normalResult.observedAfter().orElse(null), 11L));
    }

    @Test
    void timeoutAndUnsupportedClicksFailWithoutFallback() {
        MenuSnapshot opened = chest(3, 20, List.of(
                SlotChange.at(0, item("oak_log", 1, '1'))));
        MenuSnapshot moved = chest(3, 0, List.of(
                SlotChange.at(27, item("oak_log", 1, '1'))));
        MenuTransaction transaction = MenuTransaction.opening(
                MenuFamily.CHEST_3X9,
                new MenuTransactionLimits(1, 1L),
                10L);
        Assertions.assertFalse(transaction.observeOpened(opened, 12L));
        Assertions.assertEquals(
                MenuTransactionFailure.TIMEOUT,
                transaction.failure().orElseThrow());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MenuClick(0, MenuClickType.QUICK_MOVE, 1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new MenuClick(63, MenuClickType.PICKUP, 0)
                        .validateFor(MenuFamily.CHEST_3X9));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> oneStepPlan(opened, moved)
                        .snapshotAtPrefix(2));
    }

    private static MenuTransaction start(
            MenuTransactionPlan plan, MenuSnapshot opened) {
        MenuTransaction transaction = MenuTransaction.opening(
                MenuFamily.CHEST_3X9,
                new MenuTransactionLimits(2, 20L),
                10L);
        Assertions.assertTrue(transaction.observeOpened(opened, 10L));
        transaction.beginPlanning(10L);
        Assertions.assertTrue(transaction.installPlan(plan, 10L));
        return transaction;
    }

    private static MenuTransactionPlan oneStepPlan(
            MenuSnapshot opened, MenuSnapshot moved) {
        return new MenuTransactionPlan(
                MenuFamily.CHEST_3X9,
                opened,
                List.of(new MenuClickStep(
                        new MenuClick(0, MenuClickType.QUICK_MOVE, 0),
                        opened,
                        moved,
                        MenuConservationRule.strict())),
                moved);
    }

    private static MenuSnapshot chest(
            int containerId, int stateId, List<SlotChange> changes) {
        return snapshot(
                MenuFamily.CHEST_3X9, containerId, stateId, changes);
    }

    private static MenuSnapshot crafting(
            int containerId, int stateId, List<SlotChange> changes) {
        return snapshot(
                MenuFamily.CRAFTING_3X3,
                containerId,
                stateId,
                changes);
    }

    private static MenuSnapshot snapshot(
            MenuFamily family,
            int containerId,
            int stateId,
            List<SlotChange> changes) {
        List<ItemStackFingerprint> slots = new ArrayList<>();
        for (int index = 0; index < family.slotCount(); index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        for (SlotChange change : changes) {
            family.requireSlot(change.slot());
            slots.set(change.slot(), change.item());
        }
        return new MenuSnapshot(
                family,
                containerId,
                stateId,
                ItemStackFingerprint.empty(),
                slots);
    }

    private static MenuSnapshot withState(
            MenuSnapshot snapshot, int stateId) {
        return new MenuSnapshot(
                snapshot.family(),
                snapshot.containerId(),
                stateId,
                snapshot.carried(),
                snapshot.slots());
    }

    private static ItemStackFingerprint item(
            String path, int count, char digestDigit) {
        return ItemStackFingerprint.of(
                new ResourceId("minecraft:" + path),
                count,
                0,
                String.valueOf(digestDigit).repeat(64));
    }

    private static MenuItemKey key(String path, char digestDigit) {
        return new MenuItemKey(
                new ResourceId("minecraft:" + path),
                0,
                String.valueOf(digestDigit).repeat(64));
    }

    private record SlotChange(int slot, ItemStackFingerprint item) {
        private static SlotChange at(
                int slot, ItemStackFingerprint item) {
            return new SlotChange(slot, item);
        }
    }
}
