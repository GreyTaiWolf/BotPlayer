package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiClientSponsoredRequestCoordinatorTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OTHER_BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000102");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000202");
    private static final UUID TEMPLATE_REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");

    @Test
    void normalOpenRejectsSameBotAndGlobalCapacityWithoutMutatingTheActiveBinding() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator(
                        new AiClientSponsoredRequestCoordinator.Limits(1, 4, 4));
        AiClientSponsoredRequest first = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L)));

        AiClientSponsoredRequestOpenResult sameBot = coordinator.open(request(
                BOT_ID, AGENT_ID, 8L, 100L, 20, 1_000L, 2_000L));
        Assertions.assertEquals(AiClientSponsoredRequestOpenStatus.BOT_BUSY,
                sameBot.status());
        Assertions.assertTrue(sameBot.opened().isEmpty());
        Assertions.assertTrue(sameBot.replaced().isEmpty());

        AiClientSponsoredRequestOpenResult full = coordinator.open(request(
                OTHER_BOT_ID, OTHER_AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L));
        Assertions.assertEquals(AiClientSponsoredRequestOpenStatus.CAPACITY_EXHAUSTED,
                full.status());
        Assertions.assertTrue(full.opened().isEmpty());
        Assertions.assertEquals(1, coordinator.activeRequestCount());
        Assertions.assertEquals(first,
                coordinator.closeExact(first.dispatchReceipt()).orElseThrow());
    }

    @Test
    void sameBotReplacementAtGlobalCapacityReturnsTheOnlyExactDisplacedBinding() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator(
                        new AiClientSponsoredRequestCoordinator.Limits(1, 4, 4));
        AiClientSponsoredRequest first = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L)));

        AiClientSponsoredRequestOpenResult result = coordinator.openReplacing(request(
                BOT_ID, AGENT_ID, 8L, 100L, 20, 1_000L, 2_000L));
        AiClientSponsoredRequest replacement = opened(result);

        Assertions.assertEquals(first, result.replaced().orElseThrow());
        Assertions.assertNotEquals(first.dispatch().requestId(),
                replacement.dispatch().requestId());
        Assertions.assertTrue(coordinator.closeExact(first.dispatchReceipt()).isEmpty());
        Assertions.assertEquals(1, coordinator.activeRequestCount());
        Assertions.assertEquals(replacement,
                coordinator.closeExact(replacement.dispatchReceipt()).orElseThrow());
        Assertions.assertEquals(0, coordinator.activeRequestCount());
    }

    @Test
    void invalidReplacementDeadlineFailsBeforeItCanDisplaceTheOldBinding() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest first = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L)));

        Assertions.assertThrows(IllegalArgumentException.class,
                () -> coordinator.openReplacing(request(
                        BOT_ID, AGENT_ID, 8L, 100L, 20, 1_000L, 1_000L)));

        Assertions.assertEquals(1, coordinator.activeRequestCount());
        Assertions.assertEquals(first,
                coordinator.closeExact(first.dispatchReceipt()).orElseThrow());
    }

    @Test
    void exactExpiryBotCloseAndShutdownKeepGateAndLedgerClosuresInLockstep() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator(
                        new AiClientSponsoredRequestCoordinator.Limits(3, 4, 4));
        AiClientSponsoredRequest expiring = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 10, 1_000L, 1_500L)));
        AiClientSponsoredRequest retained = opened(coordinator.open(request(
                OTHER_BOT_ID, OTHER_AGENT_ID, 9L, 100L, 20, 1_000L, 2_000L)));

        Assertions.assertTrue(coordinator.closeExpiredThrough(109L).isEmpty());
        Assertions.assertEquals(List.of(expiring),
                coordinator.closeExpiredThrough(110L));
        Assertions.assertEquals(retained,
                coordinator.closeBot(OTHER_BOT_ID).orElseThrow());
        Assertions.assertTrue(coordinator.closeBot(OTHER_BOT_ID).isEmpty());

        AiClientSponsoredRequest reopenedFirst = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 10L, 200L, 20, 2_000L, 3_000L)));
        AiClientSponsoredRequest reopenedOther = opened(coordinator.open(request(
                OTHER_BOT_ID, OTHER_AGENT_ID, 11L, 200L, 20, 2_000L, 3_000L)));
        Assertions.assertEquals(List.of(reopenedFirst, reopenedOther), coordinator.closeAll());
        Assertions.assertEquals(0, coordinator.activeRequestCount());
    }

    @Test
    void workerCanOnlyOfferSafeTerminalObservationsAndDrainingDoesNotCloseTheSession() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator(
                        new AiClientSponsoredRequestCoordinator.Limits(2, 4, 1));
        AiClientSponsoredRequest binding = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L)));
        AiClientSponsoredTerminalObservation first = observation(
                binding.dispatchReceipt(), AiClientSponsoredTerminalStatus.SUCCEEDED);
        AiClientSponsoredTerminalObservation late = observation(new AiRequestDispatchReceipt(
                binding.dispatch().botId(),
                binding.dispatch().agentId(),
                binding.dispatch().generation(),
                binding.dispatch().requestId(),
                binding.dispatch().revision() + 1L,
                binding.dispatch().expiresAtTick(),
                binding.dispatch().purpose()), AiClientSponsoredTerminalStatus.FAILED);

        AtomicReference<Boolean> firstOffered = new AtomicReference<>();
        AtomicReference<Boolean> lateOffered = new AtomicReference<>();
        Throwable workerFailure = runOnWorker(() -> {
            firstOffered.set(coordinator.offerTerminalObservation(first));
            lateOffered.set(coordinator.offerTerminalObservation(late));
        });
        Assertions.assertNull(workerFailure);
        Assertions.assertEquals(Boolean.TRUE, firstOffered.get());
        Assertions.assertEquals(Boolean.TRUE, lateOffered.get());

        Assertions.assertEquals(List.of(first), coordinator.drainTerminalObservations());
        Assertions.assertEquals(1, coordinator.terminalMailboxSize());
        Assertions.assertEquals(1, coordinator.activeRequestCount());
        Assertions.assertEquals(List.of(late), coordinator.drainTerminalObservations());
        Assertions.assertEquals(binding,
                coordinator.closeExact(binding.dispatchReceipt()).orElseThrow());
    }

    @Test
    void terminalMailboxIsBoundedAndOwnerOnlyOperationsRejectForeignThreads() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator(
                        new AiClientSponsoredRequestCoordinator.Limits(1, 1, 1));
        AiClientSponsoredRequest binding = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L)));
        AiClientSponsoredTerminalObservation observation = observation(
                binding.dispatchReceipt(), AiClientSponsoredTerminalStatus.CANCELLED);

        Assertions.assertTrue(coordinator.offerTerminalObservation(observation));
        Assertions.assertFalse(coordinator.offerTerminalObservation(observation));
        Assertions.assertEquals(List.of(observation), coordinator.drainTerminalObservations());

        assertOwnerOnly(coordinator::activeRequestCount);
        assertOwnerOnly(() -> coordinator.open(request(
                OTHER_BOT_ID, OTHER_AGENT_ID, 8L, 100L, 20, 1_000L, 2_000L)));
        assertOwnerOnly(() -> coordinator.openReplacing(request(
                BOT_ID, AGENT_ID, 8L, 100L, 20, 1_000L, 2_000L)));
        assertOwnerOnly(() -> coordinator.closeExact(binding.dispatchReceipt()));
        assertOwnerOnly(() -> coordinator.closeBot(BOT_ID));
        assertOwnerOnly(() -> coordinator.closeExpiredThrough(100L));
        assertOwnerOnly(coordinator::closeAll);
        assertOwnerOnly(coordinator::drainTerminalObservations);
        assertOwnerOnly(coordinator::terminalMailboxSize);
        Assertions.assertEquals(binding,
                coordinator.closeExact(binding.dispatchReceipt()).orElseThrow());
    }

    @Test
    void diagnosticsDoNotRevealOwnerNonceOrRequestText() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequestCoordinator.OpenRequest opening = request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L);
        AiClientSponsoredRequest binding = opened(coordinator.open(opening));
        AiClientSponsoredTerminalObservation observation = observation(
                binding.dispatchReceipt(), AiClientSponsoredTerminalStatus.SUCCEEDED);

        String openingDiagnostic = opening.toString();
        String coordinatorDiagnostic = coordinator.toString();
        String observationDiagnostic = observation.toString();

        Assertions.assertFalse(openingDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(openingDiagnostic.contains("不可信请求正文"));
        Assertions.assertFalse(coordinatorDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(coordinatorDiagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(observationDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(observationDiagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(observationDiagnostic.contains("不可信请求正文"));
    }

    private static AiClientSponsoredRequest opened(
            AiClientSponsoredRequestOpenResult result) {
        Assertions.assertEquals(AiClientSponsoredRequestOpenStatus.OPENED, result.status());
        return result.opened().orElseThrow();
    }

    private static AiClientSponsoredRequestCoordinator.OpenRequest request(
            UUID botId,
            UUID agentId,
            long revision,
            long currentTick,
            int ttlTicks,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis) {
        return new AiClientSponsoredRequestCoordinator.OpenRequest(
                SERVER_ID,
                botId,
                OWNER_ID,
                agentId,
                4L,
                revision,
                currentTick,
                ttlTicks,
                AiRequestPurpose.UNSPECIFIED_V1,
                true,
                policy(),
                issuedAtEpochMillis,
                expiresAtEpochMillis,
                "deepseek",
                template());
    }

    private static AiClientSponsoredTerminalObservation observation(
            AiRequestDispatchReceipt receipt,
            AiClientSponsoredTerminalStatus status) {
        return new AiClientSponsoredTerminalObservation(receipt, status);
    }

    private static Throwable runOnWorker(Runnable action) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                action.run();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "client-sponsored-coordinator-test-worker");
        worker.start();
        try {
            worker.join();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test worker was interrupted", exception);
        }
        return failure.get();
    }

    private static void assertOwnerOnly(Runnable action) {
        Assertions.assertInstanceOf(IllegalStateException.class, runOnWorker(action));
    }

    private static AiRequest template() {
        return new AiRequest(
                TEMPLATE_REQUEST_ID,
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "不可信请求正文")),
                new AiRequestOptions(
                        512,
                        50L,
                        AiResponseFormat.TEXT,
                        false,
                        true,
                        Optional.empty()),
                Optional.empty());
    }

    private static ToolFirewallPolicy policy() {
        return new ToolFirewallPolicy(
                Map.of(), Set.of(), ToolRiskLevel.SAFE, 1);
    }
}
