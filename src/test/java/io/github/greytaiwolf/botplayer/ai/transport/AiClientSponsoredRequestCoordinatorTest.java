package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalToolCallPayload;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
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
    private static final UUID OTHER_OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000152");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000202");
    private static final UUID TEMPLATE_REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");
    private static final UUID MISMATCH_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000302");

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
    void matchingProposalIsReviewedThenExactClosedWithoutExecution() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest binding = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                acceptedPolicy())));

        AiClientSponsoredProposalReviewResult result = coordinator.reviewProposal(
                payload(binding), authorized(), 101L);

        Assertions.assertEquals(AiProposalReviewStatus.ACCEPTED_NO_EXECUTION,
                result.review().orElseThrow().status());
        Assertions.assertTrue(result.review().orElseThrow().acceptedNoExecution());
        Assertions.assertEquals(binding, result.terminalBinding().orElseThrow());
        Assertions.assertEquals(0, coordinator.activeRequestCount());
        Assertions.assertTrue(coordinator.closeExact(binding.dispatchReceipt()).isEmpty());
        Assertions.assertTrue(coordinator.reviewProposal(payload(binding),
                authorized(), 101L).review().isEmpty(),
                "a consumed correlation cannot be reviewed a second time");
    }

    @Test
    void correlationDriftDropsBeforeGateAndPreservesTheExactBinding() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest binding = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                acceptedPolicy())));

        assertDroppedBeforeGate(coordinator, payload(
                OTHER_BOT_ID, binding.dispatch().agentId(),
                binding.dispatch().generation(), binding.dispatch().requestId(),
                binding.dispatch().nonce(), binding.dispatch().revision()));
        assertDroppedBeforeGate(coordinator, payload(
                binding.dispatch().botId(), OTHER_AGENT_ID,
                binding.dispatch().generation(), binding.dispatch().requestId(),
                binding.dispatch().nonce(), binding.dispatch().revision()));
        assertDroppedBeforeGate(coordinator, payload(
                binding.dispatch().botId(), binding.dispatch().agentId(),
                binding.dispatch().generation() + 1L,
                binding.dispatch().requestId(), binding.dispatch().nonce(),
                binding.dispatch().revision()));
        assertDroppedBeforeGate(coordinator, payload(
                binding.dispatch().botId(), binding.dispatch().agentId(),
                binding.dispatch().generation(), differentUuid(
                        binding.dispatch().requestId()),
                binding.dispatch().nonce(), binding.dispatch().revision()));
        assertDroppedBeforeGate(coordinator, payload(
                binding.dispatch().botId(), binding.dispatch().agentId(),
                binding.dispatch().generation(), binding.dispatch().requestId(),
                differentUuid(binding.dispatch().nonce()),
                binding.dispatch().revision()));
        assertDroppedBeforeGate(coordinator, payload(
                binding.dispatch().botId(), binding.dispatch().agentId(),
                binding.dispatch().generation(), binding.dispatch().requestId(),
                binding.dispatch().nonce(), binding.dispatch().revision() + 1L));

        Assertions.assertEquals(binding, coordinator.reviewProposal(payload(binding),
                authorized(), 101L).terminalBinding().orElseThrow());
    }

    @Test
    void nonTerminalAuthorityReviewLeavesTheExactBindingLiveForLaterReview() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest binding = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                acceptedPolicy())));

        AiClientSponsoredProposalReviewResult rejected = coordinator.reviewProposal(
                payload(binding), new AiProposalAuthority(
                        true, false, Optional.of(OWNER_ID),
                        Optional.of(AGENT_ID), OptionalLong.of(4L)), 101L);

        Assertions.assertEquals(AiProposalReviewStatus.NOT_OWNER,
                rejected.review().orElseThrow().status());
        Assertions.assertTrue(rejected.terminalBinding().isEmpty());
        Assertions.assertEquals(1, coordinator.activeRequestCount());
        AiClientSponsoredProposalReviewResult missingGeneration = coordinator.reviewProposal(
                payload(binding), new AiProposalAuthority(
                        true, true, Optional.of(OWNER_ID),
                        Optional.of(AGENT_ID), OptionalLong.empty()), 101L);
        Assertions.assertEquals(AiProposalReviewStatus.GENERATION_MISMATCH,
                missingGeneration.review().orElseThrow().status());
        Assertions.assertTrue(missingGeneration.terminalBinding().isEmpty());
        Assertions.assertEquals(1, coordinator.activeRequestCount());
        Assertions.assertEquals(binding, coordinator.reviewProposal(payload(binding),
                authorized(), 101L).terminalBinding().orElseThrow());
    }

    @Test
    void terminalGateReviewsCloseTheExactLedgerBinding() {
        AiClientSponsoredRequestCoordinator expiredCoordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest expired = opened(expiredCoordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 1, 1_000L, 1_050L,
                acceptedPolicy())));
        assertTerminalReview(expiredCoordinator.reviewProposal(payload(expired),
                authorized(), 101L), AiProposalReviewStatus.EXPIRED, expired);
        Assertions.assertEquals(0, expiredCoordinator.activeRequestCount());

        AiClientSponsoredRequestCoordinator ownerChangedCoordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest ownerChanged = opened(ownerChangedCoordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                acceptedPolicy())));
        assertTerminalReview(ownerChangedCoordinator.reviewProposal(
                payload(ownerChanged), new AiProposalAuthority(
                        true, true, Optional.of(OTHER_OWNER_ID),
                        Optional.of(AGENT_ID), OptionalLong.of(4L)), 101L),
                AiProposalReviewStatus.OWNER_CHANGED, ownerChanged);
        Assertions.assertEquals(0, ownerChangedCoordinator.activeRequestCount());

        AiClientSponsoredRequestCoordinator malformedCoordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest malformed = opened(malformedCoordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                acceptedPolicy())));
        assertTerminalReview(malformedCoordinator.reviewProposal(payload(malformed,
                List.of(new AiProposalToolCallPayload("call_1", "safe_tool",
                        "{not-json"))), authorized(), 101L),
                AiProposalReviewStatus.MALFORMED_TOOL_CALL, malformed);
        Assertions.assertEquals(0, malformedCoordinator.activeRequestCount());

        AiClientSponsoredRequestCoordinator firewallCoordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest firewall = opened(firewallCoordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                policy())));
        assertTerminalReview(firewallCoordinator.reviewProposal(payload(firewall),
                authorized(), 101L), AiProposalReviewStatus.TOOL_REJECTED, firewall);
        Assertions.assertEquals(0, firewallCoordinator.activeRequestCount());
    }

    @Test
    void exactLedgerPrecheckFailsClosedOnSameRequestIdGenerationDisagreement() {
        AiProposalSessionGate gate = new AiProposalSessionGate();
        AiClientSponsoredRequestLedger ledger = new AiClientSponsoredRequestLedger();
        AiProposalRequestEnvelope gateEnvelope = gate.open(
                BOT_ID, OWNER_ID, AGENT_ID, 4L, 7L, 100L, 20,
                AiRequestPurpose.UNSPECIFIED_V1, true, acceptedPolicy());
        AiProposalRequestEnvelope ledgerEnvelope = new AiProposalRequestEnvelope(
                gateEnvelope.botId(), gateEnvelope.ownerId(), gateEnvelope.agentId(),
                5L, gateEnvelope.requestId(), gateEnvelope.nonce(),
                gateEnvelope.revision(), gateEnvelope.issuedAtTick(),
                gateEnvelope.expiresAtTick(), gateEnvelope.purpose(),
                gateEnvelope.toolCallsAllowed(), gateEnvelope.firewallPolicy());
        AiClientSponsoredRequest binding = AiClientSponsoredRequest.bind(
                SERVER_ID, ledgerEnvelope, 1_000L, 2_000L, "deepseek", template());
        ledger.open(binding);
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator(gate, ledger,
                        AiClientSponsoredRequestCoordinator.Limits.defaults());

        Assertions.assertThrows(IllegalStateException.class,
                () -> coordinator.reviewProposal(payload(binding), authorized(), 101L));

        Assertions.assertEquals(1, gate.activeRequestCount(),
                "the coordinator must not broadly close a disagreeing gate session");
        Assertions.assertEquals(0, ledger.activeRequestCount(),
                "the coordinator must exact-close the prechecked ledger binding");
        Assertions.assertThrows(IllegalStateException.class,
                coordinator::activeRequestCount,
                "the retained gate disagreement must keep the coordinator fail-closed");
    }

    @Test
    void replacementFencesTheOldProposalBeforeItCanReachTheGate() {
        AiClientSponsoredRequestCoordinator coordinator =
                new AiClientSponsoredRequestCoordinator();
        AiClientSponsoredRequest first = opened(coordinator.open(request(
                BOT_ID, AGENT_ID, 7L, 100L, 20, 1_000L, 2_000L,
                acceptedPolicy())));
        AiClientSponsoredRequest replacement = opened(coordinator.openReplacing(request(
                BOT_ID, AGENT_ID, 8L, 101L, 20, 1_050L, 2_050L,
                acceptedPolicy())));

        assertDroppedBeforeGate(coordinator, payload(first));
        Assertions.assertEquals(replacement, coordinator.reviewProposal(
                payload(replacement), authorized(), 102L).terminalBinding()
                .orElseThrow());
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
        assertOwnerOnly(() -> coordinator.reviewProposal(payload(binding),
                authorized(), 101L));
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
        String reviewDiagnostic = coordinator.reviewProposal(payload(binding),
                new AiProposalAuthority(true, false, Optional.of(OWNER_ID),
                        Optional.of(AGENT_ID), OptionalLong.of(4L)), 101L)
                .toString();

        Assertions.assertFalse(openingDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(openingDiagnostic.contains("不可信请求正文"));
        Assertions.assertFalse(coordinatorDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(coordinatorDiagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(observationDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(observationDiagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(observationDiagnostic.contains("不可信请求正文"));
        Assertions.assertFalse(reviewDiagnostic.contains(OWNER_ID.toString()));
        Assertions.assertFalse(reviewDiagnostic.contains(
                binding.dispatch().nonce().toString()));
        Assertions.assertFalse(reviewDiagnostic.contains("untrusted proposal"));
        Assertions.assertFalse(reviewDiagnostic.contains("safe_tool"));
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
        return request(botId, agentId, revision, currentTick, ttlTicks,
                issuedAtEpochMillis, expiresAtEpochMillis, policy());
    }

    private static AiClientSponsoredRequestCoordinator.OpenRequest request(
            UUID botId,
            UUID agentId,
            long revision,
            long currentTick,
            int ttlTicks,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis,
            ToolFirewallPolicy firewallPolicy) {
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
                firewallPolicy,
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

    private static AiProposalPayload payload(AiClientSponsoredRequest binding) {
        return payload(binding, List.of(new AiProposalToolCallPayload(
                "call_1", "safe_tool", "{}")));
    }

    private static AiProposalPayload payload(AiClientSponsoredRequest binding,
            List<AiProposalToolCallPayload> toolCalls) {
        return new AiProposalPayload(
                binding.dispatch().botId(),
                binding.dispatch().agentId(),
                binding.dispatch().generation(),
                binding.dispatch().requestId(),
                binding.dispatch().nonce(),
                binding.dispatch().revision(),
                "untrusted proposal", toolCalls);
    }

    private static AiProposalPayload payload(
            UUID botId,
            UUID agentId,
            long generation,
            UUID requestId,
            UUID nonce,
            long revision) {
        return new AiProposalPayload(botId, agentId, generation, requestId,
                nonce, revision, "untrusted proposal", List.of(
                        new AiProposalToolCallPayload("call_1", "safe_tool",
                                "{}")));
    }

    private static AiProposalAuthority authorized() {
        return new AiProposalAuthority(true, true, Optional.of(OWNER_ID),
                Optional.of(AGENT_ID), OptionalLong.of(4L));
    }

    private static UUID differentUuid(UUID value) {
        return value.equals(TEMPLATE_REQUEST_ID)
                ? MISMATCH_ID
                : TEMPLATE_REQUEST_ID;
    }

    private static void assertDroppedBeforeGate(
            AiClientSponsoredRequestCoordinator coordinator,
            AiProposalPayload payload) {
        AiClientSponsoredProposalReviewResult result = coordinator.reviewProposal(
                payload, authorized(), 101L);
        Assertions.assertTrue(result.review().isEmpty());
        Assertions.assertTrue(result.terminalBinding().isEmpty());
        Assertions.assertEquals(1, coordinator.activeRequestCount());
    }

    private static void assertTerminalReview(
            AiClientSponsoredProposalReviewResult result,
            AiProposalReviewStatus expectedStatus,
            AiClientSponsoredRequest expectedBinding) {
        Assertions.assertEquals(expectedStatus,
                result.review().orElseThrow().status());
        Assertions.assertEquals(expectedBinding,
                result.terminalBinding().orElseThrow());
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

    private static ToolFirewallPolicy acceptedPolicy() {
        return new ToolFirewallPolicy(
                Map.of("safe_tool", new ToolDefinition("safe_tool",
                        ToolRiskLevel.SAFE, Map.of())),
                Set.of("safe_tool"), ToolRiskLevel.SAFE, 1);
    }
}
