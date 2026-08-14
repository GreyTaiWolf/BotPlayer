package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestOptions;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.AiTokenUsage;
import io.github.greytaiwolf.botplayer.ai.CancellationToken;
import io.github.greytaiwolf.botplayer.ai.ProviderHealth;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlySnapshotProjection;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientAiRequestSessionControllerTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID OTHER_OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000152");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID OTHER_AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000202");
    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");

    @TempDir
    Path temporaryDirectory;

    private ScheduledExecutorService scheduler;
    private ClientCredentialStore credentialStore;
    private AtomicLong clock;

    @BeforeEach
    void setUp() throws Exception {
        scheduler = new ScheduledThreadPoolExecutor(1);
        credentialStore = new ClientCredentialStore(temporaryDirectory.resolve("credentials"));
        credentialStore.bind(
                SERVER_ID,
                OWNER_ID,
                BOT_ID,
                ClientCredentialStore.DEFAULT_PROFILE_ID,
                "fake-key-1234",
                Optional.of(AGENT_ID));
        clock = new AtomicLong(1_000L);
    }

    @AfterEach
    void tearDown() {
        if (credentialStore != null) {
            credentialStore.close();
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    @Test
    void exactLocalBindingExecutesOnceAndReturnsOnlyTheExistingProposalPayload() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(provider), returned);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(dispatch));
        Assertions.assertEquals(1, provider.calls.get());
        Assertions.assertEquals(dispatch.requestId(), provider.request.get().requestId());
        Assertions.assertFalse(provider.token.get().isCancellationRequested());

        provider.completion.complete(toolResponse(dispatch));

        Assertions.assertEquals(1, returned.size());
        AiProposalPayload proposal = returned.get(0);
        Assertions.assertEquals(dispatch.botId(), proposal.botId());
        Assertions.assertEquals(dispatch.agentId(), proposal.agentId());
        Assertions.assertEquals(dispatch.generation(), proposal.generation());
        Assertions.assertEquals(dispatch.requestId(), proposal.requestId());
        Assertions.assertEquals(dispatch.nonce(), proposal.nonce());
        Assertions.assertEquals(dispatch.revision(), proposal.revision());
        Assertions.assertEquals(1, proposal.toolCalls().size());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        Assertions.assertFalse(proposal.toString().contains("tool_argument_sentinel"));
    }

    @Test
    void fixedReviewLoopbackDropsProviderProseAndForwardsOnlyTheEmptyReviewTool() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(provider), returned);
        AiClientRequestDispatch dispatch = reviewDispatch();

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(dispatch));
        provider.completion.complete(new AiResponse(
                dispatch.requestId(),
                dispatch.providerId(),
                dispatch.model(),
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall(
                        "call_1", AiReviewOnlyContract.TOOL_NAME, "{}")),
                AiTokenUsage.empty()));

        AiProposalPayload proposal = Assertions.assertDoesNotThrow(
                () -> returned.getFirst());
        Assertions.assertEquals("", proposal.outputText());
        Assertions.assertEquals(1, proposal.toolCalls().size());
        Assertions.assertEquals(AiReviewOnlyContract.TOOL_NAME,
                proposal.toolCalls().getFirst().name());
        Assertions.assertEquals("{}", proposal.toolCalls().getFirst().argumentsJson());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
    }

    @Test
    void ownerBindingAgentAndDeadlineFailuresNeverConstructOrInvokeAProvider() throws Exception {
        ControlledProvider unused = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        ClientAiRequestSessionController wrongOwner = controller(
                OTHER_OWNER_ID, List.of(unused), returned);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.NOT_LOCAL_OWNER,
                wrongOwner.accept(dispatch));
        Assertions.assertEquals(0, unused.calls.get());

        ClientCredentialStore missingStore = new ClientCredentialStore(
                temporaryDirectory.resolve("missing"));
        try {
            ClientAiRequestSessionController missingBinding = new ClientAiRequestSessionController(
                    missingStore,
                    OWNER_ID,
                    (ignored, store) -> unused,
                    clock::get,
                    scheduler,
                    (ignored, proposal) -> returned.add(proposal));
            Assertions.assertEquals(ClientAiRequestDispatchStatus.BINDING_MISSING,
                    missingBinding.accept(dispatch));
        } finally {
            missingStore.close();
        }

        AiClientRequestDispatch wrongAgent = dispatch(REQUEST_ID, OTHER_AGENT_ID, 2_000L);
        ClientAiRequestSessionController agentMismatch = controller(
                OWNER_ID, List.of(unused), returned);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.AGENT_MISMATCH,
                agentMismatch.accept(wrongAgent));
        Assertions.assertEquals(0, unused.calls.get());

        clock.set(2_000L);
        ClientAiRequestSessionController expired = controller(
                OWNER_ID, List.of(unused), returned);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.EXPIRED,
                expired.accept(dispatch));
        Assertions.assertEquals(0, unused.calls.get());

        AiClientRequestDispatch futureIssued = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000305"),
                AGENT_ID,
                7_001L,
                8_001L);
        clock.set(1_000L);
        ClientAiRequestSessionController skewedClock = controller(
                OWNER_ID, List.of(unused), returned);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.EXPIRED,
                skewedClock.accept(futureIssued));
        Assertions.assertEquals(0, unused.calls.get());

        ClientAiRequestSessionController disconnected = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (ignored, store) -> unused,
                clock::get,
                scheduler,
                () -> false,
                (ignored, proposal) -> returned.add(proposal));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.CANCELLED,
                disconnected.accept(dispatch));
        Assertions.assertEquals(0, unused.calls.get());
        Assertions.assertTrue(returned.isEmpty());
    }

    @Test
    void toleratedClockSkewCannotExtendCredentialUsePastTheTickDerivedTtl() {
        scheduler.shutdownNow();
        CapturingDeadlineScheduler capturingScheduler = new CapturingDeadlineScheduler();
        scheduler = capturingScheduler;
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(provider), returned);
        AiClientRequestDispatch futureIssued = dispatch(
                REQUEST_ID,
                AGENT_ID,
                6_000L,
                7_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(futureIssued));
        Assertions.assertEquals(1_000L, capturingScheduler.lastDelayMillis.get());
        Assertions.assertEquals(1, provider.calls.get());
        Assertions.assertTrue(controller.cancelRequest(futureIssued.requestId()));
    }

    @Test
    void cancellationExpiryAndLocalAgentDriftSuppressLateProviderResponses() throws Exception {
        ControlledProvider first = new ControlledProvider();
        ControlledProvider second = new ControlledProvider();
        ControlledProvider third = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(first, second, third), returned);

        AiClientRequestDispatch firstDispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(firstDispatch));
        Assertions.assertTrue(controller.cancelBot(BOT_ID));
        Assertions.assertTrue(first.token.get().isCancellationRequested());
        first.completion.complete(toolResponse(firstDispatch));
        Assertions.assertTrue(returned.isEmpty());

        UUID secondRequestId = UUID.fromString("00000000-0000-0000-0000-000000000302");
        AiClientRequestDispatch secondDispatch = dispatch(secondRequestId, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(secondDispatch));
        Assertions.assertEquals(1, controller.expireThrough(2_000L));
        Assertions.assertTrue(second.token.get().isCancellationRequested());
        second.completion.complete(toolResponse(secondDispatch));
        Assertions.assertTrue(returned.isEmpty());

        clock.set(1_000L);
        UUID thirdRequestId = UUID.fromString("00000000-0000-0000-0000-000000000303");
        AiClientRequestDispatch thirdDispatch = dispatch(thirdRequestId, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(thirdDispatch));
        credentialStore.unbind(SERVER_ID, OWNER_ID, BOT_ID);
        credentialStore.bind(
                SERVER_ID,
                OWNER_ID,
                BOT_ID,
                ClientCredentialStore.DEFAULT_PROFILE_ID,
                "",
                Optional.of(OTHER_AGENT_ID));
        third.completion.complete(toolResponse(thirdDispatch));

        Assertions.assertTrue(returned.isEmpty());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(third.token.get().isCancellationRequested());
    }

    @Test
    void providerFailuresAndMismatchedResponseCorrelationNeverReachTheProposalSink() {
        ControlledProvider wrongResponse = new ControlledProvider();
        ControlledProvider failedResponse = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(wrongResponse, failedResponse), returned);

        AiClientRequestDispatch firstDispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(firstDispatch));
        wrongResponse.completion.complete(new AiResponse(
                UUID.fromString("00000000-0000-0000-0000-000000000399"),
                firstDispatch.providerId(),
                firstDispatch.model(),
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall("call_1", "safe_tool", "{}")),
                AiTokenUsage.empty()));
        Assertions.assertTrue(wrongResponse.token.get().isCancellationRequested());

        UUID secondRequestId = UUID.fromString("00000000-0000-0000-0000-000000000304");
        AiClientRequestDispatch secondDispatch = dispatch(secondRequestId, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(secondDispatch));
        failedResponse.completion.completeExceptionally(
                new IllegalStateException("provider failure sentinel"));

        Assertions.assertTrue(returned.isEmpty());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(failedResponse.token.get().isCancellationRequested());
    }

    @Test
    void invalidatedConnectionEpochSuppressesALateResponseAndCancelsItsToken() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        AtomicBoolean activeEpoch = new AtomicBoolean(true);
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (ignored, store) -> provider,
                clock::get,
                scheduler,
                activeEpoch::get,
                (ignored, proposal) -> returned.add(proposal));
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(dispatch));
        activeEpoch.set(false);
        provider.completion.complete(toolResponse(dispatch));

        Assertions.assertTrue(returned.isEmpty());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        Assertions.assertEquals(0, controller.activeRequestCount());
    }

    @Test
    void bindingEpochChangeDuringProviderSetupStopsProviderStart() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        AtomicReference<ClientAiRequestSessionController> controllerRef = new AtomicReference<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> {
                    controllerRef.get().advanceBindingEpoch(dispatch.botId());
                    return provider;
                },
                clock::get,
                scheduler,
                (dispatch, proposal) -> returned.add(proposal));
        controllerRef.set(controller);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.CANCELLED,
                controller.accept(dispatch(REQUEST_ID, AGENT_ID, 2_000L)));
        Assertions.assertEquals(0, provider.calls.get());
        Assertions.assertTrue(returned.isEmpty());
        Assertions.assertEquals(0, controller.activeRequestCount());
    }

    @Test
    void bindingEpochSuppressesQueuedCompletionAfterSameAgentProfileRebindButAllowsNewRequest()
            throws Exception {
        ControlledProvider first = new ControlledProvider();
        ControlledProvider second = new ControlledProvider();
        List<QueuedHandoff> queued = new ArrayList<>();
        List<AiProposalPayload> sent = new ArrayList<>();
        Deque<ControlledProvider> providers = new ArrayDeque<>(List.of(first, second));
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> providers.removeFirst(),
                clock::get,
                scheduler,
                () -> true,
                (dispatch, proposal, bindingEpoch) -> queued.add(new QueuedHandoff(
                        dispatch, proposal, bindingEpoch)));
        AiClientRequestDispatch firstDispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(firstDispatch));
        first.completion.complete(toolResponse(firstDispatch));
        Assertions.assertEquals(1, queued.size());
        Assertions.assertEquals(0, controller.activeRequestCount());

        /* This is the required order used by BotPlayerClient before store.bind/store.unbind. */
        controller.advanceBindingEpoch(BOT_ID);
        Assertions.assertFalse(controller.cancelBot(BOT_ID));
        ClientCredentialStore.BindResult rebound = credentialStore.bind(
                SERVER_ID,
                OWNER_ID,
                BOT_ID,
                ClientCredentialStore.DEFAULT_PROFILE_ID,
                "fake-key-5678",
                Optional.of(AGENT_ID));
        Assertions.assertEquals(AGENT_ID, rebound.binding().agentId());
        Assertions.assertFalse(queued.getFirst().bindingEpoch().isCurrentFor(BOT_ID));

        UUID secondRequestId = UUID.fromString("00000000-0000-0000-0000-000000000304");
        AiClientRequestDispatch secondDispatch = dispatch(secondRequestId, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(secondDispatch));
        second.completion.complete(toolResponse(secondDispatch));

        for (QueuedHandoff handoff : queued) {
            try {
                if (handoff.bindingEpoch().isCurrentFor(handoff.dispatch().botId())) {
                    sent.add(handoff.proposal());
                }
            } finally {
                handoff.bindingEpoch().release();
            }
        }

        Assertions.assertEquals(1, sent.size());
        Assertions.assertEquals(secondRequestId, sent.getFirst().requestId());
        Assertions.assertTrue(first.token.get().isCancellationRequested());
        Assertions.assertTrue(second.token.get().isCancellationRequested());
    }

    @Test
    void terminalRequestIdsRemainTombstonedAfterSuccessFailureAndCancellation() {
        ControlledProvider successful = new ControlledProvider();
        ControlledProvider failed = new ControlledProvider();
        ControlledProvider cancelled = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(successful, failed, cancelled), returned);

        AiClientRequestDispatch success = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(success));
        successful.completion.complete(toolResponse(success));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.DUPLICATE_REQUEST,
                controller.accept(success));

        AiClientRequestDispatch failure = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                AGENT_ID,
                2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(failure));
        failed.completion.completeExceptionally(new IllegalStateException("failure"));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.DUPLICATE_REQUEST,
                controller.accept(failure));

        AiClientRequestDispatch cancellation = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000303"),
                AGENT_ID,
                2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(cancellation));
        Assertions.assertTrue(controller.cancelBot(BOT_ID));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.DUPLICATE_REQUEST,
                controller.accept(cancellation));
        Assertions.assertEquals(3,
                successful.calls.get() + failed.calls.get() + cancelled.calls.get());
    }

    @Test
    void expiredTombstonesDoNotBlockANewRequestId() {
        ControlledProvider first = new ControlledProvider();
        ControlledProvider next = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(first, next), returned);
        AiClientRequestDispatch firstDispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(firstDispatch));
        Assertions.assertTrue(controller.cancelRequest(REQUEST_ID));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.DUPLICATE_REQUEST,
                controller.accept(firstDispatch));

        clock.set(2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.EXPIRED,
                controller.accept(firstDispatch));
        AiClientRequestDispatch nextDispatch = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000304"),
                AGENT_ID,
                2_000L,
                3_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(nextDispatch));
        Assertions.assertEquals(1, next.calls.get());
        Assertions.assertTrue(controller.cancelRequest(nextDispatch.requestId()));
    }

    @Test
    void boundedTombstonesRefuseNewWorkUntilTheirExpiryInsteadOfEvictingReplayDefense() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (ignored, store) -> provider,
                clock::get,
                scheduler,
                (ignored, proposal) -> returned.add(proposal));

        for (int index = 0;
                index < ClientAiRequestSessionController.MAX_REQUEST_TOMBSTONES;
                index++) {
            UUID requestId = new UUID(0L, 10_000L + index);
            AiClientRequestDispatch dispatch = dispatch(requestId, AGENT_ID, 2_000L);
            Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                    controller.accept(dispatch));
            Assertions.assertTrue(controller.cancelRequest(requestId));
        }

        AiClientRequestDispatch blocked = dispatch(
                new UUID(0L, 20_000L), AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.TOMBSTONE_CAPACITY,
                controller.accept(blocked));

        clock.set(2_000L);
        AiClientRequestDispatch renewed = dispatch(
                new UUID(0L, 20_001L), AGENT_ID, 2_000L, 3_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(renewed));
        Assertions.assertTrue(controller.cancelRequest(renewed.requestId()));
    }

    @Test
    void exactServerCancellationRequiresTheCurrentSessionTupleAndConnectionEpoch() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        AtomicBoolean activeEpoch = new AtomicBoolean(true);
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (ignored, store) -> provider,
                clock::get,
                scheduler,
                activeEpoch::get,
                (ignored, proposal) -> returned.add(proposal));
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(dispatch));
        AiRequestCancellationPayload exact = cancellation(dispatch);
        AiRequestCancellationPayload wrongNonce = new AiRequestCancellationPayload(
                exact.serverInstanceId(),
                exact.botId(),
                exact.ownerId(),
                exact.agentId(),
                exact.generation(),
                exact.requestId(),
                UUID.fromString("00000000-0000-0000-0000-000000000499"),
                exact.revision());
        Assertions.assertFalse(controller.cancel(wrongNonce));
        Assertions.assertFalse(provider.token.get().isCancellationRequested());

        AiRequestCancellationPayload wrongServer = new AiRequestCancellationPayload(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                exact.botId(),
                exact.ownerId(),
                exact.agentId(),
                exact.generation(),
                exact.requestId(),
                exact.nonce(),
                exact.revision());
        Assertions.assertFalse(controller.cancel(wrongServer));
        Assertions.assertFalse(provider.token.get().isCancellationRequested());

        activeEpoch.set(false);
        Assertions.assertFalse(controller.cancel(exact));
        Assertions.assertFalse(provider.token.get().isCancellationRequested());
        activeEpoch.set(true);
        Assertions.assertTrue(controller.cancel(exact));
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(returned.isEmpty());
    }

    @Test
    void oversizedProviderProposalAndToolsForbiddenByDispatchNeverReachTheSink() {
        ControlledProvider oversized = new ControlledProvider();
        ControlledProvider toolsForbidden = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(oversized, toolsForbidden), returned);
        AiClientRequestDispatch oversizedDispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(oversizedDispatch));
        oversized.completion.complete(new AiResponse(
                oversizedDispatch.requestId(),
                oversizedDispatch.providerId(),
                oversizedDispatch.model(),
                AiFinishReason.TOOL_CALLS,
                "x".repeat(20_000),
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall("call_1", "safe_tool", "x".repeat(16_000))),
                AiTokenUsage.empty()));
        Assertions.assertTrue(returned.isEmpty());
        Assertions.assertTrue(oversized.token.get().isCancellationRequested());

        AiClientRequestDispatch toolsForbiddenDispatch = dispatchWithToolCallsAllowed(
                UUID.fromString("00000000-0000-0000-0000-000000000304"), false);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(toolsForbiddenDispatch));
        toolsForbidden.completion.complete(toolResponse(toolsForbiddenDispatch));
        Assertions.assertTrue(returned.isEmpty());
        Assertions.assertTrue(toolsForbidden.token.get().isCancellationRequested());
        Assertions.assertEquals(0, controller.activeRequestCount());
    }

    private ClientAiRequestSessionController controller(
            UUID localOwnerId,
            List<ControlledProvider> providers,
            List<AiProposalPayload> returned) {
        Deque<ControlledProvider> queued = new ArrayDeque<>(providers);
        return new ClientAiRequestSessionController(
                credentialStore,
                localOwnerId,
                (dispatch, store) -> {
                    return queued.removeFirst();
                },
                clock::get,
                scheduler,
                (dispatch, proposal) -> returned.add(proposal));
    }

    private static AiClientRequestDispatch dispatch(
            UUID requestId, UUID agentId, long expiresAtEpochMillis) {
        return dispatch(requestId, agentId, 1_000L, expiresAtEpochMillis);
    }

    private static AiClientRequestDispatch dispatch(
            UUID requestId,
            UUID agentId,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                agentId,
                2L,
                requestId,
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                3L,
                100L,
                120L,
                issuedAtEpochMillis,
                expiresAtEpochMillis,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "request")),
                new AiRequestOptions(
                        256,
                        500L,
                        AiResponseFormat.TEXT,
                        false,
                        true,
                        Optional.empty()),
                Optional.empty());
    }

    private static AiClientRequestDispatch dispatchWithToolCallsAllowed(
            UUID requestId, boolean toolCallsAllowed) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                2L,
                requestId,
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                3L,
                100L,
                120L,
                1_000L,
                2_000L,
                "deepseek",
                "deepseek-chat",
                List.of(new AiMessage(AiMessageRole.USER, "request")),
                new AiRequestOptions(
                        256,
                        500L,
                        AiResponseFormat.TEXT,
                        false,
                        toolCallsAllowed,
                        Optional.empty()),
                Optional.empty());
    }

    private static AiClientRequestDispatch reviewDispatch() {
        AiReviewOnlySnapshotProjection projection = new AiReviewOnlySnapshotProjection(
                BOT_ID,
                2L,
                7L,
                100L,
                "minecraft:overworld",
                19.5F,
                18,
                294,
                0);
        AiRequest template = AiReviewOnlyContract.requestTemplate(projection);
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                2L,
                REQUEST_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                projection.snapshotId(),
                AiRequestPurpose.REVIEW_ONLY_V1,
                100L,
                140L,
                1_000L,
                3_000L,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.MODEL,
                template.messages(),
                template.options(),
                Optional.empty());
    }

    private static AiRequestCancellationPayload cancellation(
            AiClientRequestDispatch dispatch) {
        return new AiRequestCancellationPayload(
                dispatch.serverInstanceId(),
                dispatch.botId(),
                dispatch.ownerId(),
                dispatch.agentId(),
                dispatch.generation(),
                dispatch.requestId(),
                dispatch.nonce(),
                dispatch.revision());
    }

    private static AiResponse toolResponse(AiClientRequestDispatch dispatch) {
        return new AiResponse(
                dispatch.requestId(),
                dispatch.providerId(),
                dispatch.model(),
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.of("reasoning is intentionally not returned"),
                Optional.empty(),
                List.of(new AiRawToolCall(
                        "call_1", "safe_tool", "{\"value\":\"tool_argument_sentinel\"}")),
                AiTokenUsage.empty());
    }

    private record QueuedHandoff(
            AiClientRequestDispatch dispatch,
            AiProposalPayload proposal,
            ClientAiRequestSessionController.BindingEpochHandoff bindingEpoch) {}

    private static final class ControlledProvider implements AiProvider {
        private final CompletableFuture<AiResponse> completion = new CompletableFuture<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<AiRequest> request = new AtomicReference<>();
        private final AtomicReference<CancellationToken> token = new AtomicReference<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            calls.incrementAndGet();
            this.request.set(request);
            this.token.set(token);
            return completion;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.failedFuture(new UnsupportedOperationException());
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.unknown("deepseek", Instant.EPOCH);
        }
    }

    private static final class CapturingDeadlineScheduler
            extends ScheduledThreadPoolExecutor {
        private final AtomicLong lastDelayMillis = new AtomicLong(-1L);

        private CapturingDeadlineScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            lastDelayMillis.set(unit.toMillis(delay));
            return super.schedule(command, delay, unit);
        }
    }
}
