package io.github.greytaiwolf.botplayer.action;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ActionCleanupProtocolTest {
    private static final UUID CLEANUP =
            new UUID(1L, 1L);
    private static final UUID BOT = new UUID(2L, 2L);

    @Test
    void retryRetainsIdentityAndAdvancesTickAndAttempt() {
        ActionEnvelope envelope = envelope();
        ActionCleanupRequest first =
                ActionCleanupRequest.first(
                        CLEANUP,
                        envelope,
                        ActionCleanupReason.CANCELLED,
                        10L);
        ActionCleanupReceipt pending =
                ActionCleanupReceipt.pending(
                        first, 1L, 11L, "等待下一 Tick");

        ActionCleanupRequest second = first.next(pending, 11L);

        Assertions.assertEquals(
                first.cleanupId(), second.cleanupId());
        Assertions.assertEquals(
                first.actionId(), second.actionId());
        Assertions.assertEquals(1, first.attempt());
        Assertions.assertEquals(2, second.attempt());
        Assertions.assertEquals(10L, second.requestedTick());
        Assertions.assertEquals(11L, second.currentTick());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> first.next(pending, 10L));
    }

    @Test
    void pendingReceiptRequiresFutureRetryAndExactAttempt() {
        ActionCleanupRequest first = request();
        ActionCleanupReceipt pending =
                ActionCleanupReceipt.pending(
                        first, 3L, 12L, "等待下一安全步骤");

        Assertions.assertTrue(pending.matches(first));
        Assertions.assertEquals(
                ActionCleanupStatus.PENDING,
                pending.status());
        Assertions.assertEquals(12L, pending.nextRetryTick());
        Assertions.assertFalse(
                pending.matches(first.next(pending, 12L)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ActionCleanupReceipt.pending(
                        first, 3L, 10L, "非法同 Tick 重试"));
    }

    @Test
    void receiptBindsReasonAndEveryRequestTick() {
        ActionCleanupRequest request = request();
        ActionCleanupReceipt receipt =
                ActionCleanupReceipt.complete(
                        request, 2L, "精确回执");
        ActionCleanupRequest changedReason =
                ActionCleanupRequest.first(
                        request.cleanupId(),
                        envelope(),
                        ActionCleanupReason.PREEMPTED,
                        request.currentTick());
        ActionCleanupRequest changedAttemptTick =
                ActionCleanupRequest.first(
                        request.cleanupId(),
                        envelope(),
                        request.reason(),
                        request.currentTick() + 1L);

        Assertions.assertFalse(receipt.matches(changedReason));
        Assertions.assertFalse(receipt.matches(changedAttemptTick));
    }

    @Test
    void receiptMatchesEveryRequestFieldExactly() {
        ActionCleanupRequest request = request();
        ActionCleanupReceipt exact =
                ActionCleanupReceipt.complete(
                        request, 2L, "精确回执");
        List<ActionCleanupReceipt> changed = List.of(
                terminalReceipt(
                        new UUID(7L, 7L),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        new UUID(7L, 7L),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        new UUID(7L, 7L),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration() + 1L,
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        ActionCleanupReason.PREEMPTED,
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick() - 1L,
                        request.currentTick(),
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick() + 1L,
                        request.attempt()),
                terminalReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt() + 1));

        Assertions.assertTrue(exact.matches(request));
        changed.forEach(receipt ->
                Assertions.assertFalse(receipt.matches(request)));
    }

    @Test
    void directPendingReceiptCannotBypassFutureTickRule() {
        ActionCleanupRequest request = request();

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ActionCleanupReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        request.attempt(),
                        ActionCleanupStatus.PENDING,
                        0L,
                        request.currentTick(),
                        "非法同 Tick 重试"));
    }

    @Test
    void retryConsumesOnlyItsOwnPendingReceipt() {
        ActionCleanupRequest request = request();
        ActionCleanupReceipt pending =
                ActionCleanupReceipt.pending(
                        request, 1L, 20L, "等待授权 Tick");
        ActionCleanupReceipt terminal =
                ActionCleanupReceipt.complete(
                        request, 1L, "已经终止");
        ActionCleanupRequest other =
                ActionCleanupRequest.first(
                        new UUID(8L, 8L),
                        envelope(),
                        request.reason(),
                        request.currentTick());
        ActionCleanupReceipt otherPending =
                ActionCleanupReceipt.pending(
                        other, 1L, 20L, "其他事务");

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> request.next(pending, 19L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> request.next(terminal, 20L));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> request.next(otherPending, 20L));
        Assertions.assertEquals(
                2, request.next(pending, 21L).attempt());
    }

    @Test
    void finalAttemptCannotProducePendingReceipt() {
        ActionCleanupRequest request = request();
        for (int attempt = 1;
                attempt < ActionCleanupRequest.MAX_ATTEMPTS;
                attempt++) {
            ActionCleanupReceipt pending =
                    ActionCleanupReceipt.pending(
                            request,
                            attempt,
                            request.currentTick() + 1L,
                            "推进清理");
            request = request.next(
                    pending, request.currentTick() + 1L);
        }
        ActionCleanupRequest finalRequest = request;

        Assertions.assertEquals(
                ActionCleanupRequest.MAX_ATTEMPTS,
                finalRequest.attempt());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ActionCleanupReceipt.pending(
                        finalRequest,
                        finalRequest.attempt(),
                        finalRequest.currentTick() + 1L,
                        "非法悬空"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ActionCleanupReceipt(
                        finalRequest.cleanupId(),
                        finalRequest.actionId(),
                        finalRequest.botId(),
                        finalRequest.botGeneration(),
                        finalRequest.reason(),
                        finalRequest.requestedTick(),
                        finalRequest.currentTick(),
                        finalRequest.attempt(),
                        ActionCleanupStatus.PENDING,
                        finalRequest.attempt(),
                        finalRequest.currentTick() + 1L,
                        "非法悬空"));
    }

    @Test
    void cleanupIdStaysWithOneTicketAndDiffersAcrossTickets() {
        ActionCleanupRequest first = request();
        ActionCleanupReceipt pending =
                ActionCleanupReceipt.pending(
                        first, 1L, 11L, "等待下一 Tick");
        ActionCleanupRequest second = first.next(pending, 11L);
        ActionCleanupRequest other =
                ActionCleanupRequest.first(
                        new UUID(6L, 6L),
                        envelope(),
                        first.reason(),
                        first.currentTick());

        Assertions.assertEquals(
                first.cleanupId(), second.cleanupId());
        Assertions.assertNotEquals(
                first.cleanupId(), other.cleanupId());
    }

    @Test
    void directReceiptConstructorEnforcesStatusCoherence() {
        ActionCleanupRequest request = request();

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.COMPLETE,
                        0L,
                        request.currentTick() + 1L,
                        "终态错误重试"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.UNSAFE,
                        0L,
                        request.currentTick() + 1L,
                        "终态错误重试"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.PENDING,
                        -1L,
                        request.currentTick() + 1L,
                        "进度非法"));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> receipt(
                        request, null, 0L, -1L, "状态为空"));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.COMPLETE,
                        0L,
                        -1L,
                        null));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.COMPLETE,
                        0L,
                        -1L,
                        " 前后空白"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.COMPLETE,
                        0L,
                        -1L,
                        "控制\n字符"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> receipt(
                        request,
                        ActionCleanupStatus.COMPLETE,
                        0L,
                        -1L,
                        "x".repeat(
                                ActionCleanupReceipt.MAX_SUMMARY_LENGTH
                                        + 1)));
    }

    @Test
    void terminalReceiptCannotExposeRetryTick() {
        ActionCleanupRequest request = request();
        ActionCleanupReceipt complete =
                ActionCleanupReceipt.complete(
                        request, 4L, "安全端点已确认");
        ActionCleanupReceipt unsafe =
                ActionCleanupReceipt.unsafe(
                        request, 5L, "无法证明安全");

        Assertions.assertEquals(-1L, complete.nextRetryTick());
        Assertions.assertEquals(-1L, unsafe.nextRetryTick());
        Assertions.assertEquals(
                ActionCleanupStatus.COMPLETE,
                complete.status());
        Assertions.assertEquals(
                ActionCleanupStatus.UNSAFE,
                unsafe.status());
    }

    @Test
    void requestRejectsChangedActionIdentity() {
        ActionCleanupRequest request = request();
        ActionEnvelope changed = new ActionEnvelope(
                new UUID(9L, 9L),
                BOT,
                4L,
                "cleanup/changed",
                100L,
                5,
                new StopAction(),
                ActionOrigin.none());

        Assertions.assertFalse(request.matches(changed));
    }

    @Test
    void legacyAdapterRejectsEveryEnvelopeIdentityBeforeCleanup() {
        LegacyBackend backend = new LegacyBackend();
        ActionEnvelope exact = envelope();
        ActionCleanupRequest request =
                ActionCleanupRequest.first(
                        CLEANUP,
                        exact,
                        ActionCleanupReason.CANCELLED,
                        10L);
        List<ActionEnvelope> changed = List.of(
                envelope(
                        new UUID(7L, 7L),
                        exact.botId(),
                        exact.botGeneration(),
                        "cleanup/wrong-action"),
                envelope(
                        exact.actionId(),
                        new UUID(7L, 7L),
                        exact.botGeneration(),
                        "cleanup/wrong-bot"),
                envelope(
                        exact.actionId(),
                        exact.botId(),
                        exact.botGeneration() + 1L,
                        "cleanup/wrong-generation"));

        changed.forEach(envelope ->
                Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> backend.cleanupStep(
                                envelope, request)));
        Assertions.assertEquals(0, backend.cleanupCount);
    }

    @Test
    void legacyBackendAdapterReturnsExactCompleteReceipt() {
        LegacyBackend backend = new LegacyBackend();
        ActionEnvelope envelope = envelope();
        ActionCleanupRequest request =
                ActionCleanupRequest.first(
                        CLEANUP,
                        envelope,
                        ActionCleanupReason.PREEMPTED,
                        10L);

        ActionCleanupReceipt receipt =
                backend.cleanupStep(envelope, request);

        Assertions.assertTrue(receipt.matches(request));
        Assertions.assertEquals(
                ActionCleanupStatus.COMPLETE,
                receipt.status());
        Assertions.assertEquals(1, backend.cleanupCount);
        Assertions.assertEquals(
                ActionCleanupReason.PREEMPTED,
                backend.lastReason);
    }

    @Test
    void vanillaDeathConsumedCleanupCanOnlyCompleteOnce() {
        ActionCleanupRequest request =
                ActionCleanupRequest.first(
                        CLEANUP,
                        envelope(),
                        ActionCleanupReason.VANILLA_DEATH_CONSUMED,
                        10L);

        Assertions.assertTrue(request.vanillaDeathConsumed());
        Assertions.assertEquals(
                ActionCleanupStatus.COMPLETE,
                ActionCleanupReceipt.complete(
                        request, 0L, "死亡状态已由原版消费")
                        .status());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ActionCleanupReceipt.pending(
                        request, 0L, 11L, "不得重试"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ActionCleanupReceipt.unsafe(
                        request, 0L, "不得转为不安全"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ActionCleanupReceipt(
                        request.cleanupId(),
                        request.actionId(),
                        request.botId(),
                        request.botGeneration(),
                        request.reason(),
                        request.requestedTick(),
                        request.currentTick(),
                        2,
                        ActionCleanupStatus.COMPLETE,
                        0L,
                        -1L,
                        "不得伪造第二次完成"));
    }

    @Test
    void vanillaDeathConsumedRequestRejectsRetryEvenWithReceipt() {
        ActionCleanupRequest request =
                ActionCleanupRequest.first(
                        CLEANUP,
                        envelope(),
                        ActionCleanupReason.VANILLA_DEATH_CONSUMED,
                        10L);
        ActionCleanupReceipt complete =
                ActionCleanupReceipt.complete(
                        request, 0L, "已完成");

        Assertions.assertThrows(
                IllegalStateException.class,
                () -> request.next(complete, 11L));
    }

    private static ActionCleanupRequest request() {
        return ActionCleanupRequest.first(
                CLEANUP,
                envelope(),
                ActionCleanupReason.CANCELLED,
                10L);
    }

    private static ActionEnvelope envelope() {
        return envelope(
                new UUID(3L, 3L),
                BOT,
                4L,
                "cleanup/exact");
    }

    private static ActionEnvelope envelope(
            UUID actionId,
            UUID botId,
            long generation,
            String idempotencyKey) {
        return new ActionEnvelope(
                actionId,
                botId,
                generation,
                idempotencyKey,
                100L,
                5,
                new StopAction(),
                ActionOrigin.none());
    }

    private static ActionCleanupReceipt terminalReceipt(
            UUID cleanupId,
            UUID actionId,
            UUID botId,
            long botGeneration,
            ActionCleanupReason reason,
            long requestedTick,
            long attemptedTick,
            int attempt) {
        return new ActionCleanupReceipt(
                cleanupId,
                actionId,
                botId,
                botGeneration,
                reason,
                requestedTick,
                attemptedTick,
                attempt,
                ActionCleanupStatus.COMPLETE,
                0L,
                -1L,
                "字段变更");
    }

    private static ActionCleanupReceipt receipt(
            ActionCleanupRequest request,
            ActionCleanupStatus status,
            long progressRevision,
            long nextRetryTick,
            String summary) {
        return new ActionCleanupReceipt(
                request.cleanupId(),
                request.actionId(),
                request.botId(),
                request.botGeneration(),
                request.reason(),
                request.requestedTick(),
                request.currentTick(),
                request.attempt(),
                status,
                progressRevision,
                nextRetryTick,
                summary);
    }

    private static final class LegacyBackend
            implements ActionBackend {
        private int cleanupCount;
        private ActionCleanupReason lastReason;

        @Override
        public BackendResult validate(
                ActionEnvelope envelope, long currentTick) {
            return BackendResult.accepted(envelope);
        }

        @Override
        public BackendResult start(
                ActionEnvelope envelope, long currentTick) {
            return BackendResult.running(envelope);
        }

        @Override
        public BackendResult tick(
                ActionEnvelope envelope,
                long startedTick,
                long currentTick) {
            return BackendResult.running(envelope);
        }

        @Override
        public BackendResult verify(
                ActionEnvelope envelope, long currentTick) {
            return BackendResult.succeeded(
                    envelope, List.of(), "完成");
        }

        @Override
        public void cleanup(
                ActionEnvelope envelope,
                ActionCleanupReason reason,
                long currentTick) {
            cleanupCount++;
            lastReason = reason;
        }

        @Override
        public boolean forceSafeReset(
                UUID botId,
                long botGeneration,
                long currentTick) {
            return true;
        }
    }
}
