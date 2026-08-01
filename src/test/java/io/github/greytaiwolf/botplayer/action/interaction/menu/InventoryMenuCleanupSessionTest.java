package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.ActionCleanupReason;
import io.github.greytaiwolf.botplayer.action.ActionCleanupReceipt;
import io.github.greytaiwolf.botplayer.action.ActionCleanupRequest;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryMenuCleanupSessionTest {
    private static final UUID CLEANUP = new UUID(1L, 1L);
    private static final UUID BOT = new UUID(2L, 2L);
    private static final UUID ACTION = new UUID(3L, 3L);
    private static final ItemStackFingerprint EMPTY =
            ItemStackFingerprint.empty();

    @Test
    void replaysOnlyTheExactRememberedRequest() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest first = request(CLEANUP);

        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(first).status());
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REJECTED,
                session.begin(first).status());
        ActionCleanupReceipt pending =
                ActionCleanupReceipt.pending(
                        first, 0L, 11L, "继续");
        session.remember(first, pending);

        InventoryMenuCleanupSession.BeginResult replay =
                session.begin(first);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REPLAY,
                replay.status());
        Assertions.assertTrue(
                pending == replay.replayReceipt().orElseThrow());

        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REJECTED,
                session.begin(request(new UUID(9L, 9L))).status());
        ActionCleanupRequest second = pendingRequest(
                first, pending, 11L);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(second).status());
    }

    @Test
    void rememberRequiresTheOpenAttemptAndExactRevision() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest first = request(CLEANUP);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(first).status());
        session.recordConfirmedForwardProgress();
        ActionCleanupReceipt staleRevision =
                ActionCleanupReceipt.pending(
                        first, 0L, 11L, "旧进度");

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session.remember(
                        first, staleRevision));
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REJECTED,
                session.begin(first).status());

        ActionCleanupReceipt exact =
                ActionCleanupReceipt.pending(
                        first,
                        session.progressRevision(),
                        11L,
                        "精确进度");
        session.remember(first, exact);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REPLAY,
                session.begin(first).status());
    }

    @Test
    void retryCannotPrecedeTheRememberedReceiptTick() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest first = request(CLEANUP);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(first).status());
        ActionCleanupReceipt remembered =
                ActionCleanupReceipt.pending(
                        first, 0L, 20L, "等待授权 Tick");
        session.remember(first, remembered);
        ActionCleanupReceipt forgedEarlier =
                ActionCleanupReceipt.pending(
                        first, 0L, 11L, "伪造更早 Tick");
        ActionCleanupRequest tooEarly = pendingRequest(
                first, forgedEarlier, 11L);

        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REJECTED,
                session.begin(tooEarly).status());
        ActionCleanupRequest authorized = pendingRequest(
                first, remembered, 20L);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(authorized).status());
    }

    @Test
    void replaysAnExactTerminalReceiptWithoutChangingProgress() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest request = request(CLEANUP);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(request).status());
        session.recordConfirmedForwardProgress();
        ActionCleanupReceipt complete =
                ActionCleanupReceipt.complete(
                        request,
                        session.progressRevision(),
                        "完成");
        session.remember(request, complete);

        InventoryMenuCleanupSession.BeginResult replay =
                session.begin(request);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REPLAY,
                replay.status());
        Assertions.assertTrue(
                complete == replay.replayReceipt().orElseThrow());
        Assertions.assertEquals(1L, session.progressRevision());
    }

    @Test
    void terminalReceiptRejectsAPreconstructedLaterAttempt() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest first = request(CLEANUP);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(first).status());
        ActionCleanupReceipt firstPending =
                ActionCleanupReceipt.pending(
                        first, 0L, 11L, "第一步");
        session.remember(first, firstPending);
        ActionCleanupRequest second = pendingRequest(
                first, firstPending, 11L);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(second).status());
        ActionCleanupReceipt secondPending =
                ActionCleanupReceipt.pending(
                        second, 0L, 12L, "预构造下一步");
        ActionCleanupRequest third = pendingRequest(
                second, secondPending, 12L);
        ActionCleanupReceipt terminal =
                ActionCleanupReceipt.complete(
                        second, 0L, "终态");
        session.remember(second, terminal);

        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REJECTED,
                session.begin(third).status());
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REPLAY,
                session.begin(second).status());
    }

    @Test
    void freezesEndpointAndAdvancesRevisionOncePerConfirmedClick() {
        InventoryMenuSwapPlan plan = fiveStepPlan();
        InventoryMenuSnapshot prefixThree =
                plan.snapshotAtPrefix(3);
        InventoryMenuSettlementDecision first =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        prefixThree,
                        InventoryMenuPrefixAuthority.stable(
                                plan, 3, prefixThree));
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();

        InventoryMenuSettlementCursor frozen =
                session.observe(first);
        Assertions.assertEquals(
                InventoryMenuSettlementCursor.Endpoint.FINAL,
                frozen.endpoint());
        Assertions.assertEquals(0L, session.progressRevision());
        Assertions.assertTrue(frozen == session.observe(first));

        InventoryMenuSettlementCursor prefixFour =
                session.confirmProposedClick(first);
        Assertions.assertEquals(4, prefixFour.confirmedPrefix());
        Assertions.assertEquals(1L, session.progressRevision());
        InventoryMenuSnapshot fourth =
                plan.snapshotAtPrefix(4);
        InventoryMenuSettlementDecision second =
                InventoryMenuSettlementPolicy.decide(
                        prefixFour,
                        fourth,
                        InventoryMenuPrefixAuthority.stable(
                                plan, 4, fourth));
        InventoryMenuSettlementCursor terminal =
                session.confirmProposedClick(second);

        Assertions.assertTrue(terminal.atEndpoint());
        Assertions.assertEquals(2L, session.progressRevision());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session.observe(first));
    }

    @Test
    void finalAttemptCannotRemainPending() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest request = request(CLEANUP);
        for (int attempt = 1;
                attempt < ActionCleanupRequest.MAX_ATTEMPTS;
                attempt++) {
            Assertions.assertEquals(
                    InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                    session.begin(request).status());
            ActionCleanupReceipt pending =
                    ActionCleanupReceipt.pending(
                            request,
                            session.progressRevision(),
                            request.currentTick() + 1L,
                            "继续");
            session.remember(request, pending);
            request = pendingRequest(
                    request,
                    pending,
                    pending.nextRetryTick());
        }

        Assertions.assertEquals(
                ActionCleanupRequest.MAX_ATTEMPTS,
                request.attempt());
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(request).status());
        ActionCleanupRequest finalRequest = request;
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ActionCleanupReceipt.pending(
                        finalRequest,
                        session.progressRevision(),
                        finalRequest.currentTick() + 1L,
                        "不能继续"));
    }

    @Test
    void reservesOnlyOneClickDispatchPerTick() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();

        Assertions.assertTrue(session.mayDispatchClickAt(20L));
        session.beginClickDispatch(20L);
        Assertions.assertTrue(session.clickDispatchOpen());
        Assertions.assertFalse(session.mayDispatchClickAt(20L));
        Assertions.assertFalse(session.mayDispatchClickAt(21L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session.endClickDispatch(21L));
        Assertions.assertTrue(session.clickDispatchOpen());
        session.endClickDispatch(20L);
        Assertions.assertFalse(session.clickDispatchOpen());
        Assertions.assertFalse(session.mayDispatchClickAt(19L));
        Assertions.assertTrue(session.mayDispatchClickAt(21L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session.beginClickDispatch(20L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> session.endClickDispatch(20L));
        session.beginClickDispatch(21L);
        session.endClickDispatch(21L);
        Assertions.assertFalse(session.mayDispatchClickAt(21L));
    }

    @Test
    void carriesForwardProgressIntoTheNextCleanupAttempt() {
        InventoryMenuCleanupSession session =
                new InventoryMenuCleanupSession();
        ActionCleanupRequest first = request(CLEANUP);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(first).status());
        ActionCleanupReceipt pending =
                ActionCleanupReceipt.pending(
                        first, 0L, 11L, "等待前向调用返回");
        session.remember(first, pending);

        session.recordConfirmedForwardProgress();
        InventoryMenuCleanupSession.BeginResult replay =
                session.begin(first);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.REPLAY,
                replay.status());
        Assertions.assertTrue(
                pending == replay.replayReceipt().orElseThrow());
        Assertions.assertEquals(0L, pending.progressRevision());
        Assertions.assertEquals(1L, session.progressRevision());

        ActionCleanupRequest second = pendingRequest(
                first, pending, 11L);
        Assertions.assertEquals(
                InventoryMenuCleanupSession.BeginStatus.NEW_ATTEMPT,
                session.begin(second).status());
        ActionCleanupReceipt next =
                ActionCleanupReceipt.pending(
                        second,
                        session.progressRevision(),
                        12L,
                        "已接管新进度");
        Assertions.assertTrue(
                next == session.remember(second, next));
    }

    private static ActionCleanupRequest request(UUID cleanupId) {
        return ActionCleanupRequest.first(
                cleanupId,
                new ActionEnvelope(
                        ACTION,
                        BOT,
                        4L,
                        "menu/cleanup/session",
                        100L,
                        5,
                        new StopAction(),
                        ActionOrigin.none()),
                ActionCleanupReason.PREEMPTED,
                10L);
    }

    private static ActionCleanupRequest pendingRequest(
            ActionCleanupRequest request,
            ActionCleanupReceipt receipt,
            long retryTick) {
        return request.next(receipt, retryTick);
    }

    private static InventoryMenuSwapPlan fiveStepPlan() {
        return InventoryMenuSwapPlanBuilder.swapSequence(
                snapshot(
                        0, item("stone", '1'),
                        1, item("dirt", '2'),
                        9, item("torch", '3'),
                        10, item("stick", '4'),
                        11, item("coal", '5')),
                List.of(
                        new InventoryMenuSwapInstruction(9, 0),
                        new InventoryMenuSwapInstruction(10, 0),
                        new InventoryMenuSwapInstruction(11, 1),
                        new InventoryMenuSwapInstruction(9, 1),
                        new InventoryMenuSwapInstruction(10, 1)));
    }

    private static InventoryMenuSnapshot snapshot(
            Object... slotAndFingerprintPairs) {
        List<ItemStackFingerprint> slots =
                new ArrayList<>(41);
        for (int index = 0; index < 41; index++) {
            slots.add(EMPTY);
        }
        for (int index = 0;
                index < slotAndFingerprintPairs.length;
                index += 2) {
            slots.set(
                    (Integer) slotAndFingerprintPairs[index],
                    (ItemStackFingerprint)
                            slotAndFingerprintPairs[index + 1]);
        }
        return new InventoryMenuSnapshot(
                0, 17, 4, EMPTY, slots);
    }

    private static ItemStackFingerprint item(
            String path, char digestDigit) {
        return ItemStackFingerprint.of(
                new ResourceId("minecraft:" + path),
                1,
                0,
                String.valueOf(digestDigit).repeat(64));
    }
}
