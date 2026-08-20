package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptClientGrantStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOffer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptStartGrant;
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
import io.github.greytaiwolf.botplayer.ai.transport.AiClientSponsoredTerminalObservation;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientSponsoredTerminalStatus;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Delayed;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
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
    private static final UUID SECOND_BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000102");
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
    void physicalAttemptStagesBeforeGrantAndStartsProviderExactlyOnceAfterClaim() {
        ControlledProvider provider = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = controller(
                OWNER_ID, List.of(provider), returned);
        AiClientRequestDispatch dispatch = reviewDispatch();
        AiPhysicalAttemptOffer offer = physicalAttemptOffer(dispatch);

        ClientAiPhysicalAttemptPreparation preparation = controller.preparePhysicalAttempt(
                offer, dispatch);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.PREPARED,
                preparation.status());
        Assertions.assertEquals(offer.prepareAck(), preparation.prepareAck().orElseThrow());
        Assertions.assertEquals(0, provider.calls.get());
        Assertions.assertEquals(1, controller.activeRequestCount());

        AiPhysicalAttemptStartGrant grant = new AiPhysicalAttemptStartGrant(offer.identity());
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.HANDED_OFF,
                controller.acceptPhysicalAttemptStartGrant(grant));
        Assertions.assertEquals(1, provider.calls.get());
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF,
                controller.acceptPhysicalAttemptStartGrant(grant));
        Assertions.assertEquals(1, provider.calls.get());

        provider.completion.complete(reviewToolResponse(dispatch));

        Assertions.assertEquals(1, returned.size());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
    }

    @Test
    void physicalAttemptNeverStartsProviderAfterCancellationRebindOrPhysicalDeadline() {
        ControlledProvider cancelledProvider = new ControlledProvider();
        ClientAiRequestSessionController cancelled = controller(
                OWNER_ID, List.of(cancelledProvider), new ArrayList<>());
        AiClientRequestDispatch cancelledDispatch = reviewDispatch();
        AiPhysicalAttemptOffer cancelledOffer = physicalAttemptOffer(cancelledDispatch);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.PREPARED,
                cancelled.preparePhysicalAttempt(cancelledOffer, cancelledDispatch).status());
        Assertions.assertTrue(cancelled.cancelRequest(cancelledDispatch.requestId()));
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.LOCAL_SESSION_INACTIVE,
                cancelled.acceptPhysicalAttemptStartGrant(new AiPhysicalAttemptStartGrant(
                        cancelledOffer.identity())));
        Assertions.assertEquals(0, cancelledProvider.calls.get());

        ControlledProvider reboundProvider = new ControlledProvider();
        ClientAiRequestSessionController rebound = controller(
                OWNER_ID, List.of(reboundProvider), new ArrayList<>());
        AiClientRequestDispatch reboundDispatch = reviewDispatch(UUID.fromString(
                "00000000-0000-0000-0000-000000000302"));
        AiPhysicalAttemptOffer reboundOffer = physicalAttemptOffer(reboundDispatch);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.PREPARED,
                rebound.preparePhysicalAttempt(reboundOffer, reboundDispatch).status());
        rebound.advanceBindingEpoch(BOT_ID);
        Assertions.assertEquals(AiPhysicalAttemptClientGrantStatus.LOCAL_BINDING_EPOCH_MISMATCH,
                rebound.acceptPhysicalAttemptStartGrant(new AiPhysicalAttemptStartGrant(
                        reboundOffer.identity())));
        Assertions.assertEquals(0, reboundProvider.calls.get());

        ControlledProvider expiredProvider = new ControlledProvider();
        ClientAiRequestSessionController expired = controller(
                OWNER_ID, List.of(expiredProvider), new ArrayList<>());
        AiClientRequestDispatch expiredDispatch = reviewDispatch(UUID.fromString(
                "00000000-0000-0000-0000-000000000303"));
        AiPhysicalAttemptOffer expiredOffer = physicalAttemptOffer(expiredDispatch);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.PREPARED,
                expired.preparePhysicalAttempt(expiredOffer, expiredDispatch).status());
        clock.set(expiredOffer.identity().physicalStartNotAfterEpochMillis());
        Assertions.assertEquals(
                AiPhysicalAttemptClientGrantStatus.PHYSICAL_START_NOT_AFTER_EXPIRED,
                expired.acceptPhysicalAttemptStartGrant(new AiPhysicalAttemptStartGrant(
                        expiredOffer.identity())));
        Assertions.assertEquals(0, expiredProvider.calls.get());
    }

    @Test
    void physicalAttemptRejectsMismatchedOrNonR1OffersBeforeProviderConstruction() {
        ControlledProvider provider = new ControlledProvider();
        AtomicInteger factoryCalls = new AtomicInteger();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (ignored, store) -> {
                    factoryCalls.incrementAndGet();
                    return provider;
                },
                clock::get,
                scheduler,
                (ignored, proposal) -> {});
        AiClientRequestDispatch dispatch = reviewDispatch();
        AiPhysicalAttemptOffer mismatchedOffer = physicalAttemptOffer(reviewDispatch(UUID.fromString(
                "00000000-0000-0000-0000-000000000304")));

        ClientAiPhysicalAttemptPreparation preparation = controller.preparePhysicalAttempt(
                mismatchedOffer, dispatch);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED,
                preparation.status());
        Assertions.assertTrue(preparation.prepareAck().isEmpty());
        Assertions.assertEquals(0, factoryCalls.get());
        Assertions.assertEquals(0, provider.calls.get());
        Assertions.assertEquals(0, controller.activeRequestCount());

        AiClientRequestDispatch nonR1Dispatch = dispatch(UUID.fromString(
                "00000000-0000-0000-0000-000000000305"), AGENT_ID, 2_000L);
        ClientAiPhysicalAttemptPreparation nonR1Preparation = controller.preparePhysicalAttempt(
                physicalAttemptOffer(nonR1Dispatch), nonR1Dispatch);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED,
                nonR1Preparation.status());
        Assertions.assertTrue(nonR1Preparation.prepareAck().isEmpty());
        Assertions.assertEquals(0, factoryCalls.get());
        Assertions.assertEquals(0, provider.calls.get());
        Assertions.assertEquals(0, controller.activeRequestCount());
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

    @Test
    void terminalObserverPublishesOnlySafeExactStatusForSuccessFailureAndCancellation() {
        ControlledProvider successful = new ControlledProvider();
        ControlledProvider failed = new ControlledProvider();
        ControlledProvider cancelled = new ControlledProvider();
        List<AiProposalPayload> returned = new ArrayList<>();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        Deque<ControlledProvider> providers = new ArrayDeque<>(
                List.of(successful, failed, cancelled));
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> providers.removeFirst(),
                clock::get,
                scheduler,
                () -> true,
                (dispatch, proposal) -> returned.add(proposal),
                observations::add);
        AiClientRequestDispatch success = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        AiClientRequestDispatch failure = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                AGENT_ID,
                2_000L);
        AiClientRequestDispatch cancellation = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000303"),
                AGENT_ID,
                2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED, controller.accept(success));
        successful.completion.complete(toolResponse(success));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED, controller.accept(failure));
        failed.completion.completeExceptionally(new IllegalStateException("provider sentinel"));
        Assertions.assertEquals(
                ClientAiRequestDispatchStatus.STARTED, controller.accept(cancellation));
        Assertions.assertTrue(controller.cancelRequest(cancellation.requestId()));

        Assertions.assertEquals(1, returned.size());
        Assertions.assertEquals(List.of(
                AiClientSponsoredTerminalStatus.SUCCEEDED,
                AiClientSponsoredTerminalStatus.FAILED,
                AiClientSponsoredTerminalStatus.CANCELLED), observations.stream()
                .map(AiClientSponsoredTerminalObservation::status)
                .toList());
        Assertions.assertEquals(AiRequestDispatchReceipt.fromDispatch(success),
                observations.get(0).receipt());
        Assertions.assertEquals(AiRequestDispatchReceipt.fromDispatch(failure),
                observations.get(1).receipt());
        Assertions.assertEquals(AiRequestDispatchReceipt.fromDispatch(cancellation),
                observations.get(2).receipt());
        String rendered = observations.toString();
        Assertions.assertFalse(rendered.contains(success.nonce().toString()));
        Assertions.assertFalse(rendered.contains(success.ownerId().toString()));
        Assertions.assertFalse(rendered.contains("tool_argument_sentinel"));
        Assertions.assertFalse(rendered.contains("deepseek-chat"));
        Assertions.assertFalse(rendered.contains("prompt_sentinel"));
    }

    @Test
    void providerSetupFailureIsObservedAsFailedAfterTheSessionWasRegistered() {
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> {
                    throw new IllegalStateException("factory sentinel");
                },
                clock::get,
                scheduler,
                (dispatch, proposal) -> Assertions.fail("proposal must not be handed off"),
                observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE,
                controller.accept(dispatch));
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                AiRequestDispatchReceipt.fromDispatch(dispatch),
                AiClientSponsoredTerminalStatus.FAILED)), observations);
    }

    @Test
    void rejectedIngressDoesNotCreateATerminalObservation() {
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OTHER_OWNER_ID,
                (dispatch, store) -> Assertions.fail("provider must not be constructed"),
                clock::get,
                scheduler,
                (dispatch, proposal) -> Assertions.fail("proposal must not be handed off"),
                observations::add);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.NOT_LOCAL_OWNER,
                controller.accept(dispatch(REQUEST_ID, AGENT_ID, 2_000L)));
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(observations.isEmpty());
    }

    @Test
    void observerRuntimeFailureDoesNotRetainACompletedSession() {
        ControlledProvider provider = new ControlledProvider();
        AtomicInteger observerCalls = new AtomicInteger();
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> provider,
                clock::get,
                scheduler,
                (dispatch, proposal) -> returned.add(proposal),
                observation -> {
                    observerCalls.incrementAndGet();
                    throw new IllegalStateException("observer sentinel");
                });
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED, controller.accept(dispatch));
        Assertions.assertTrue(provider.completion.complete(toolResponse(dispatch)));

        Assertions.assertEquals(1, observerCalls.get());
        Assertions.assertEquals(1, returned.size());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
    }

    @Test
    void postAdmissionErrorsFromExternalSetupBoundariesFailClosedBeforeRethrow() {
        scheduler.shutdownNow();
        scheduler = new ErroringDeadlineScheduler();
        AtomicInteger schedulerFactoryCalls = new AtomicInteger();
        List<AiClientSponsoredTerminalObservation> schedulerObservations =
                new ArrayList<>();
        ClientAiRequestSessionController schedulerController =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> {
                            schedulerFactoryCalls.incrementAndGet();
                            return new ControlledProvider();
                        },
                        clock::get,
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "scheduler failure must not hand off a proposal"),
                        schedulerObservations::add);
        AiClientRequestDispatch schedulerDispatch = dispatch(
                REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertThrows(AssertionError.class,
                () -> schedulerController.accept(schedulerDispatch));
        Assertions.assertEquals(0, schedulerFactoryCalls.get());
        assertFailedTerminal(schedulerController, schedulerObservations,
                schedulerDispatch);

        scheduler.shutdownNow();
        scheduler = new ErroringCancellationDeadlineScheduler();
        List<AiClientSponsoredTerminalObservation> factoryObservations =
                new ArrayList<>();
        ClientAiRequestSessionController factoryController =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> {
                            throw new AssertionError("factory sentinel");
                        },
                        clock::get,
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "factory failure must not hand off a proposal"),
                        factoryObservations::add);
        AiClientRequestDispatch factoryDispatch = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                AGENT_ID, 2_000L);

        AssertionError factoryError = Assertions.assertThrows(AssertionError.class,
                () -> factoryController.accept(factoryDispatch));
        Assertions.assertEquals("factory sentinel", factoryError.getMessage());
        Assertions.assertEquals(1, factoryError.getSuppressed().length);
        Assertions.assertEquals("deadline cancellation sentinel",
                factoryError.getSuppressed()[0].getMessage());
        assertFailedTerminal(factoryController, factoryObservations,
                factoryDispatch);

        scheduler.shutdownNow();
        scheduler = new ScheduledThreadPoolExecutor(1);
        ThrowingCompleteProvider completeProvider =
                new ThrowingCompleteProvider();
        List<AiClientSponsoredTerminalObservation> completeObservations =
                new ArrayList<>();
        ClientAiRequestSessionController completeController =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> completeProvider,
                        clock::get,
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "provider failure must not hand off a proposal"),
                        completeObservations::add);
        AiClientRequestDispatch completeDispatch = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000303"),
                AGENT_ID, 2_000L);

        Assertions.assertThrows(AssertionError.class,
                () -> completeController.accept(completeDispatch));
        Assertions.assertTrue(
                completeProvider.token.get().isCancellationRequested());
        assertFailedTerminal(completeController, completeObservations,
                completeDispatch);

        AttachmentErrorProvider attachmentProvider =
                new AttachmentErrorProvider();
        List<AiClientSponsoredTerminalObservation> attachmentObservations =
                new ArrayList<>();
        ClientAiRequestSessionController attachmentController =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> attachmentProvider,
                        clock::get,
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "attachment failure must not hand off a proposal"),
                        attachmentObservations::add);
        AiClientRequestDispatch attachmentDispatch = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000304"),
                AGENT_ID, 2_000L);

        Assertions.assertThrows(AssertionError.class,
                () -> attachmentController.accept(attachmentDispatch));
        Assertions.assertTrue(
                attachmentProvider.token.get().isCancellationRequested());
        assertFailedTerminal(attachmentController, attachmentObservations,
                attachmentDispatch);
    }

    @Test
    void callbackThenAttachmentErrorCannotPublishAProvisionalSuccess() {
        scheduler.shutdownNow();
        scheduler = new ErroringCancellationDeadlineScheduler();
        List<AiProposalPayload> proposals = new ArrayList<>();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        CallbackThenThrowAttachmentProvider provider =
                new CallbackThenThrowAttachmentProvider(toolResponse(dispatch));
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (checkedDispatch, store) -> provider,
                        clock::get,
                        scheduler,
                        (checkedDispatch, proposal) -> proposals.add(proposal),
                        observations::add);

        AssertionError attachmentError = Assertions.assertThrows(
                AssertionError.class, () -> controller.accept(dispatch));

        Assertions.assertEquals("callback attachment sentinel", attachmentError.getMessage());
        Assertions.assertEquals(1, attachmentError.getSuppressed().length);
        Assertions.assertEquals("deadline cancellation sentinel",
                attachmentError.getSuppressed()[0].getMessage());
        Assertions.assertTrue(proposals.isEmpty());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        assertFailedTerminal(controller, observations, dispatch);
    }

    @Test
    void completionTimeClockErrorDetachesAndObservesTheRegisteredSession() {
        ControlledProvider provider = new ControlledProvider();
        AtomicInteger clockReads = new AtomicInteger();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> provider,
                        () -> {
                            if (clockReads.incrementAndGet() >= 4) {
                                throw new AssertionError("completion clock sentinel");
                            }
                            return 1_000L;
                        },
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "completion clock failure must not hand off a proposal"),
                        observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(dispatch));
        Assertions.assertTrue(provider.completion.complete(toolResponse(dispatch)));

        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        assertFailedTerminal(controller, observations, dispatch);
    }

    @Test
    void completionErrorDetachesBeforeItsCancellationListenerCanClaimCancelled() {
        ControlledProvider provider = new ControlledProvider();
        AtomicInteger clockReads = new AtomicInteger();
        AtomicBoolean listenerCancelled = new AtomicBoolean();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> provider,
                        () -> {
                            if (clockReads.incrementAndGet() >= 4) {
                                throw new AssertionError("completion clock sentinel");
                            }
                            return 1_000L;
                        },
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "completion clock failure must not hand off a proposal"),
                        observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(dispatch));
        provider.token.get().onCancellation(() -> listenerCancelled.set(
                controller.cancelRequest(dispatch.requestId())));
        Assertions.assertTrue(provider.completion.complete(toolResponse(dispatch)));

        Assertions.assertFalse(listenerCancelled.get());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        assertFailedTerminal(controller, observations, dispatch);
    }

    @Test
    void completionErrorRetainsItsPrimaryWhenDeadlineCancellationAlsoFails() {
        scheduler.shutdownNow();
        scheduler = new ErroringCancellationDeadlineScheduler();
        AtomicInteger clockReads = new AtomicInteger();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        SynchronousCallbackProvider provider = new SynchronousCallbackProvider(
                toolResponse(dispatch));
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (checkedDispatch, store) -> provider,
                        () -> {
                            if (clockReads.incrementAndGet() >= 4) {
                                throw new AssertionError("completion clock sentinel");
                            }
                            return 1_000L;
                        },
                        scheduler,
                        (checkedDispatch, proposal) -> Assertions.fail(
                                "completion clock failure must not hand off a proposal"),
                        observations::add);

        AssertionError completionError = Assertions.assertThrows(
                AssertionError.class, () -> controller.accept(dispatch));

        Assertions.assertEquals("completion clock sentinel", completionError.getMessage());
        Assertions.assertEquals(1, completionError.getSuppressed().length);
        Assertions.assertEquals("deadline cancellation sentinel",
                completionError.getSuppressed()[0].getMessage());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        assertFailedTerminal(controller, observations, dispatch);
    }

    @Test
    void handoffErrorRetainsItsPrimaryWhenDeadlineCancellationAlsoFails() {
        scheduler.shutdownNow();
        scheduler = new ErroringCancellationDeadlineScheduler();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        SynchronousCallbackProvider provider = new SynchronousCallbackProvider(
                toolResponse(dispatch));
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (checkedDispatch, store) -> provider,
                        clock::get,
                        scheduler,
                        () -> true,
                        (checkedDispatch, proposal, bindingEpoch) -> {
                            throw new AssertionError("handoff sentinel");
                        },
                        observations::add);

        AssertionError handoffError = Assertions.assertThrows(
                AssertionError.class, () -> controller.accept(dispatch));

        Assertions.assertEquals("handoff sentinel", handoffError.getMessage());
        Assertions.assertEquals(1, handoffError.getSuppressed().length);
        Assertions.assertEquals("deadline cancellation sentinel",
                handoffError.getSuppressed()[0].getMessage());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        assertFailedTerminal(controller, observations, dispatch);
    }

    @Test
    void deadlineCleanupErrorDoesNotRewriteAnAlreadySuccessfulTerminalStatus() {
        scheduler.shutdownNow();
        scheduler = new ErroringCancellationDeadlineScheduler();
        List<AiProposalPayload> proposals = new ArrayList<>();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        SynchronousCallbackProvider provider = new SynchronousCallbackProvider(
                toolResponse(dispatch));
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (checkedDispatch, store) -> provider,
                        clock::get,
                        scheduler,
                        (checkedDispatch, proposal) -> proposals.add(proposal),
                        observations::add);

        AssertionError cleanupError = Assertions.assertThrows(
                AssertionError.class, () -> controller.accept(dispatch));

        Assertions.assertEquals("deadline cancellation sentinel", cleanupError.getMessage());
        Assertions.assertEquals(1, proposals.size());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                AiRequestDispatchReceipt.fromDispatch(dispatch),
                AiClientSponsoredTerminalStatus.SUCCEEDED)), observations);
    }

    @Test
    void terminalOwnerReportsDeferredDeadlineErrorAfterAStaleCompletionRaces() throws Exception {
        scheduler.shutdownNow();
        scheduler = new ErroringCancellationDeadlineScheduler();
        ControlledProvider provider = new ControlledProvider();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> provider,
                        clock::get,
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "stale completion must not hand off a proposal"),
                        observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        CountDownLatch cancellationListenerEntered = new CountDownLatch(1);
        CountDownLatch releaseCancellationListener = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                    controller.accept(dispatch));
            provider.token.get().onCancellation(() -> {
                cancellationListenerEntered.countDown();
                try {
                    if (!releaseCancellationListener.await(1L, TimeUnit.SECONDS)) {
                        throw new AssertionError("cancellation listener release timed out");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError("cancellation listener was interrupted", exception);
                }
            });

            Future<Boolean> cancellation = executor.submit(
                    () -> controller.cancelRequest(dispatch.requestId()));
            Assertions.assertTrue(cancellationListenerEntered.await(1L, TimeUnit.SECONDS));
            Future<Boolean> completion = executor.submit(
                    () -> provider.completion.complete(toolResponse(dispatch)));
            Assertions.assertTrue(completion.get(1L, TimeUnit.SECONDS));

            releaseCancellationListener.countDown();
            ExecutionException cancellationFailure = Assertions.assertThrows(
                    ExecutionException.class, () -> cancellation.get(1L, TimeUnit.SECONDS));
            Assertions.assertInstanceOf(
                    AssertionError.class, cancellationFailure.getCause());
            Assertions.assertEquals("deadline cancellation sentinel",
                    cancellationFailure.getCause().getMessage());
            Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                    AiRequestDispatchReceipt.fromDispatch(dispatch),
                    AiClientSponsoredTerminalStatus.CANCELLED)), observations);
            Assertions.assertEquals(0, controller.activeRequestCount());
            Assertions.assertTrue(provider.token.get().isCancellationRequested());
        } finally {
            releaseCancellationListener.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void cancellationMarkerPreventsLateDeadlineInstallationAfterTerminalClosure()
            throws Exception {
        scheduler.shutdownNow();
        BlockingDeadlineScheduler blockingScheduler = new BlockingDeadlineScheduler();
        scheduler = blockingScheduler;
        ControlledProvider provider = new ControlledProvider();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller =
                new ClientAiRequestSessionController(
                        credentialStore,
                        OWNER_ID,
                        (dispatch, store) -> provider,
                        clock::get,
                        scheduler,
                        (dispatch, proposal) -> Assertions.fail(
                                "cancelled request must not hand off a proposal"),
                        observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ClientAiRequestDispatchStatus> acceptance = executor.submit(
                    () -> controller.accept(dispatch));
            Assertions.assertTrue(blockingScheduler.scheduleEntered.await(
                    1L, TimeUnit.SECONDS));

            Assertions.assertTrue(controller.cancelRequest(dispatch.requestId()));
            blockingScheduler.releaseSchedule.countDown();

            Assertions.assertEquals(ClientAiRequestDispatchStatus.CANCELLED,
                    acceptance.get(1L, TimeUnit.SECONDS));
            Assertions.assertEquals(1,
                    blockingScheduler.deadline.cancelCalls.get());
            Assertions.assertEquals(0, provider.calls.get());
            Assertions.assertEquals(0, controller.activeRequestCount());
            Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                    AiRequestDispatchReceipt.fromDispatch(dispatch),
                    AiClientSponsoredTerminalStatus.CANCELLED)), observations);
        } finally {
            blockingScheduler.releaseSchedule.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void proposalHandoffReentrancyFailsClosedAndPublishesAfterTheLockIsReleased() {
        ControlledProvider provider = new ControlledProvider();
        AtomicReference<ClientAiRequestSessionController> controllerRef = new AtomicReference<>();
        AtomicBoolean observerRanOutsideTheLock = new AtomicBoolean();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ExecutorService observerExecutor = Executors.newSingleThreadExecutor();
        try {
            ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                    credentialStore,
                    OWNER_ID,
                    (dispatch, store) -> provider,
                    clock::get,
                    scheduler,
                    () -> true,
                    (dispatch, proposal, bindingEpoch) -> controllerRef.get().cancelRequest(
                            dispatch.requestId()),
                    observation -> {
                        try {
                            Future<Integer> activeCount = observerExecutor.submit(
                                    () -> controllerRef.get().activeRequestCount());
                            Assertions.assertEquals(0, activeCount.get(1L, TimeUnit.SECONDS));
                        } catch (Exception exception) {
                            throw new AssertionError(
                                    "terminal observer was invoked while the controller lock held",
                                    exception);
                        }
                        observerRanOutsideTheLock.set(true);
                        observations.add(observation);
                    });
            controllerRef.set(controller);
            AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

            Assertions.assertEquals(
                    ClientAiRequestDispatchStatus.STARTED, controller.accept(dispatch));
            Assertions.assertTrue(provider.completion.complete(toolResponse(dispatch)));

            Assertions.assertTrue(observerRanOutsideTheLock.get());
            Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                    AiRequestDispatchReceipt.fromDispatch(dispatch),
                    AiClientSponsoredTerminalStatus.FAILED)), observations);
            Assertions.assertEquals(0, controller.activeRequestCount());
            Assertions.assertTrue(provider.token.get().isCancellationRequested());
        } finally {
            observerExecutor.shutdownNow();
        }
    }

    @Test
    void indirectProposalHandoffCompletionFailsBothSessionsOutsideTheLock() throws Exception {
        credentialStore.bind(
                SERVER_ID,
                OWNER_ID,
                SECOND_BOT_ID,
                ClientCredentialStore.DEFAULT_PROFILE_ID,
                "",
                Optional.of(OTHER_AGENT_ID));
        ControlledProvider firstProvider = new ControlledProvider();
        ControlledProvider secondProvider = new ControlledProvider();
        AiClientRequestDispatch first = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        AiClientRequestDispatch second = dispatchForBot(
                SECOND_BOT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                OTHER_AGENT_ID,
                2_000L);
        AtomicReference<ClientAiRequestSessionController> controllerRef = new AtomicReference<>();
        AtomicReference<ClientAiRequestSessionController.BindingEpochHandoff>
                firstQueuedLease = new AtomicReference<>();
        AtomicInteger firstHandoffs = new AtomicInteger();
        AtomicInteger secondHandoffs = new AtomicInteger();
        AtomicInteger observersOutsideTheLock = new AtomicInteger();
        AtomicBoolean observerRanUnderTheLock = new AtomicBoolean();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> {
                    if (dispatch.requestId().equals(first.requestId())) {
                        return firstProvider;
                    }
                    if (dispatch.requestId().equals(second.requestId())) {
                        return secondProvider;
                    }
                    throw new IllegalArgumentException("unexpected test dispatch");
                },
                clock::get,
                scheduler,
                () -> true,
                (dispatch, proposal, bindingEpoch) -> {
                    if (dispatch.requestId().equals(first.requestId())) {
                        firstHandoffs.incrementAndGet();
                        firstQueuedLease.set(bindingEpoch);
                        Assertions.assertTrue(secondProvider.completion.complete(
                                toolResponse(second)));
                    } else if (dispatch.requestId().equals(second.requestId())) {
                        secondHandoffs.incrementAndGet();
                    } else {
                        Assertions.fail("unexpected proposal handoff");
                    }
                },
                observation -> {
                    try {
                        controllerRef.get().activeRequestCount();
                        observersOutsideTheLock.incrementAndGet();
                    } catch (IllegalStateException exception) {
                        observerRanUnderTheLock.set(true);
                    }
                    observations.add(observation);
                });
        controllerRef.set(controller);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(first));
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(second));
        Assertions.assertTrue(firstProvider.completion.complete(toolResponse(first)));

        Assertions.assertEquals(1, firstHandoffs.get());
        Assertions.assertEquals(0, secondHandoffs.get(),
                "the nested completion must never enter a second proposal handoff");
        Assertions.assertNotNull(firstQueuedLease.get());
        Assertions.assertFalse(firstQueuedLease.get().isCurrentFor(first.botId()),
                "the outer queued proposal must be invalidated by the indirect completion");
        Assertions.assertFalse(observerRanUnderTheLock.get());
        Assertions.assertEquals(2, observersOutsideTheLock.get());
        Assertions.assertEquals(List.of(
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(first),
                        AiClientSponsoredTerminalStatus.FAILED),
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(second),
                        AiClientSponsoredTerminalStatus.FAILED)), observations);
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(firstProvider.token.get().isCancellationRequested());
        Assertions.assertTrue(secondProvider.token.get().isCancellationRequested());
        Assertions.assertFalse(controller.cancelRequest(first.requestId()));
        Assertions.assertFalse(controller.cancelRequest(second.requestId()));
    }

    @Test
    void indirectCompletionCleanupKeepsTheOuterHandoffErrorPrimary() throws Exception {
        credentialStore.bind(
                SERVER_ID,
                OWNER_ID,
                SECOND_BOT_ID,
                ClientCredentialStore.DEFAULT_PROFILE_ID,
                "",
                Optional.of(OTHER_AGENT_ID));
        AiClientRequestDispatch first = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        AiClientRequestDispatch second = dispatchForBot(
                SECOND_BOT_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                OTHER_AGENT_ID,
                2_000L);
        SynchronousCallbackProvider firstProvider = new SynchronousCallbackProvider(
                toolResponse(first));
        ControlledProvider secondProvider = new ControlledProvider();
        AtomicReference<ClientAiRequestSessionController.BindingEpochHandoff>
                firstQueuedLease = new AtomicReference<>();
        AtomicInteger secondHandoffs = new AtomicInteger();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> {
                    if (dispatch.requestId().equals(first.requestId())) {
                        return firstProvider;
                    }
                    if (dispatch.requestId().equals(second.requestId())) {
                        return secondProvider;
                    }
                    throw new IllegalArgumentException("unexpected test dispatch");
                },
                clock::get,
                scheduler,
                () -> true,
                (dispatch, proposal, bindingEpoch) -> {
                    if (dispatch.requestId().equals(first.requestId())) {
                        firstQueuedLease.set(bindingEpoch);
                        Assertions.assertTrue(secondProvider.completion.complete(
                                toolResponse(second)));
                        throw new AssertionError("handoff sentinel");
                    }
                    if (dispatch.requestId().equals(second.requestId())) {
                        secondHandoffs.incrementAndGet();
                        return;
                    }
                    Assertions.fail("unexpected proposal handoff");
                },
                observations::add);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                controller.accept(second));
        secondProvider.token.get().onCancellation(() -> {
            throw new AssertionError("nested cleanup sentinel");
        });

        AssertionError handoffError = Assertions.assertThrows(
                AssertionError.class, () -> controller.accept(first));

        Assertions.assertEquals("handoff sentinel", handoffError.getMessage());
        Assertions.assertEquals(1, handoffError.getSuppressed().length);
        Assertions.assertEquals("nested cleanup sentinel",
                handoffError.getSuppressed()[0].getMessage());
        Assertions.assertNotNull(firstQueuedLease.get());
        Assertions.assertFalse(firstQueuedLease.get().isCurrentFor(first.botId()));
        Assertions.assertEquals(0, secondHandoffs.get());
        Assertions.assertEquals(List.of(
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(first),
                        AiClientSponsoredTerminalStatus.FAILED),
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(second),
                        AiClientSponsoredTerminalStatus.FAILED)), observations);
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(firstProvider.token.get().isCancellationRequested());
        Assertions.assertTrue(secondProvider.token.get().isCancellationRequested());
    }

    @Test
    void cancellationListenerErrorStillPublishesTheTerminalObservation() {
        ControlledProvider provider = new ControlledProvider();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> provider,
                clock::get,
                scheduler,
                (dispatch, proposal) -> Assertions.fail("proposal must not be handed off"),
                observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED, controller.accept(dispatch));
        provider.token.get().onCancellation(() -> {
            throw new AssertionError("cancellation listener sentinel");
        });

        Assertions.assertThrows(AssertionError.class,
                () -> controller.cancelRequest(dispatch.requestId()));
        Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                AiRequestDispatchReceipt.fromDispatch(dispatch),
                AiClientSponsoredTerminalStatus.CANCELLED)), observations);
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
    }

    @Test
    void replacementCancellationErrorAlsoRetiresTheNewSessionBeforeRethrowing() {
        ControlledProvider provider = new ControlledProvider();
        List<AiClientSponsoredTerminalObservation> observations = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> provider,
                clock::get,
                scheduler,
                (dispatch, proposal) -> Assertions.fail("proposal must not be handed off"),
                observations::add);
        AiClientRequestDispatch first = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        AiClientRequestDispatch replacement = dispatch(
                UUID.fromString("00000000-0000-0000-0000-000000000302"),
                AGENT_ID,
                2_000L);

        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED, controller.accept(first));
        provider.token.get().onCancellation(() -> {
            throw new AssertionError("replacement cancellation listener sentinel");
        });

        Assertions.assertThrows(AssertionError.class, () -> controller.accept(replacement));
        Assertions.assertEquals(List.of(
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(first),
                        AiClientSponsoredTerminalStatus.CANCELLED),
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(replacement),
                        AiClientSponsoredTerminalStatus.FAILED)), observations);
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertEquals(1, provider.calls.get());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
    }

    @Test
    void completionAndExactCancellationRaceProduceOneTerminalObservation() throws Exception {
        ControlledProvider provider = new ControlledProvider();
        List<AiClientSponsoredTerminalObservation> observations = Collections.synchronizedList(
                new ArrayList<>());
        List<AiProposalPayload> returned = new ArrayList<>();
        ClientAiRequestSessionController controller = new ClientAiRequestSessionController(
                credentialStore,
                OWNER_ID,
                (dispatch, store) -> provider,
                clock::get,
                scheduler,
                (dispatch, proposal) -> returned.add(proposal),
                observations::add);
        AiClientRequestDispatch dispatch = dispatch(REQUEST_ID, AGENT_ID, 2_000L);
        Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED, controller.accept(dispatch));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> completion = executor.submit(() -> {
                await(start);
                provider.completion.complete(toolResponse(dispatch));
            });
            Future<Boolean> cancellation = executor.submit(() -> {
                await(start);
                return controller.cancelRequest(dispatch.requestId());
            });
            start.countDown();
            completion.get(5L, TimeUnit.SECONDS);
            cancellation.get(5L, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        Assertions.assertEquals(1, observations.size());
        Assertions.assertEquals(AiRequestDispatchReceipt.fromDispatch(dispatch),
                observations.getFirst().receipt());
        Assertions.assertTrue(List.of(
                AiClientSponsoredTerminalStatus.SUCCEEDED,
                AiClientSponsoredTerminalStatus.CANCELLED).contains(
                        observations.getFirst().status()));
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertTrue(provider.token.get().isCancellationRequested());
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

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("test worker was interrupted", exception);
        }
    }

    private static void assertFailedTerminal(
            ClientAiRequestSessionController controller,
            List<AiClientSponsoredTerminalObservation> observations,
            AiClientRequestDispatch dispatch) {
        Assertions.assertEquals(0, controller.activeRequestCount());
        Assertions.assertEquals(List.of(new AiClientSponsoredTerminalObservation(
                AiRequestDispatchReceipt.fromDispatch(dispatch),
                AiClientSponsoredTerminalStatus.FAILED)), observations);
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
        return dispatchForBot(
                BOT_ID, requestId, agentId, issuedAtEpochMillis, expiresAtEpochMillis);
    }

    private static AiClientRequestDispatch dispatchForBot(
            UUID botId,
            UUID requestId,
            UUID agentId,
            long expiresAtEpochMillis) {
        return dispatchForBot(botId, requestId, agentId, 1_000L, expiresAtEpochMillis);
    }

    private static AiClientRequestDispatch dispatchForBot(
            UUID botId,
            UUID requestId,
            UUID agentId,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis) {
        return new AiClientRequestDispatch(
                SERVER_ID,
                botId,
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
                List.of(new AiMessage(AiMessageRole.USER, "prompt_sentinel")),
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
                List.of(new AiMessage(AiMessageRole.USER, "prompt_sentinel")),
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
        return reviewDispatch(REQUEST_ID);
    }

    private static AiClientRequestDispatch reviewDispatch(UUID requestId) {
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
                requestId,
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

    private static AiPhysicalAttemptOffer physicalAttemptOffer(
            AiClientRequestDispatch dispatch) {
        return new AiPhysicalAttemptOffer(AiPhysicalAttemptIdentity.fromDispatch(
                dispatch,
                UUID.fromString("00000000-0000-0000-0000-000000000501"),
                dispatch.expiresAtEpochMillis() - 1L));
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

    private static AiResponse reviewToolResponse(AiClientRequestDispatch dispatch) {
        return new AiResponse(
                dispatch.requestId(),
                dispatch.providerId(),
                dispatch.model(),
                AiFinishReason.TOOL_CALLS,
                "",
                Optional.empty(),
                Optional.empty(),
                List.of(new AiRawToolCall(
                        "call_1", AiReviewOnlyContract.TOOL_NAME, "{}")),
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

    private static final class ThrowingCompleteProvider implements AiProvider {
        private final AtomicReference<CancellationToken> token =
                new AtomicReference<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            this.token.set(token);
            throw new AssertionError("provider completion sentinel");
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

    private static final class AttachmentErrorProvider implements AiProvider {
        private final AtomicReference<CancellationToken> token =
                new AtomicReference<>();

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            this.token.set(token);
            return new AttachmentErrorStage();
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

    private static final class CallbackThenThrowAttachmentProvider implements AiProvider {
        private final AiResponse response;
        private final AtomicReference<CancellationToken> token =
                new AtomicReference<>();

        private CallbackThenThrowAttachmentProvider(AiResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            this.token.set(token);
            return new CallbackThenThrowAttachmentStage(response);
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

    private static final class SynchronousCallbackProvider implements AiProvider {
        private final AiResponse response;
        private final AtomicReference<CancellationToken> token =
                new AtomicReference<>();

        private SynchronousCallbackProvider(AiResponse response) {
            this.response = response;
        }

        @Override
        public CompletionStage<AiResponse> complete(
                AiRequest request, CancellationToken token) {
            this.token.set(token);
            return new SynchronousCallbackStage(response);
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

    private static final class AttachmentErrorStage
            extends CompletableFuture<AiResponse> {
        @Override
        public CompletableFuture<AiResponse> whenComplete(
                BiConsumer<? super AiResponse, ? super Throwable> action) {
            throw new AssertionError("completion attachment sentinel");
        }
    }

    private static final class CallbackThenThrowAttachmentStage
            extends CompletableFuture<AiResponse> {
        private final AiResponse response;

        private CallbackThenThrowAttachmentStage(AiResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<AiResponse> whenComplete(
                BiConsumer<? super AiResponse, ? super Throwable> action) {
            action.accept(response, null);
            throw new AssertionError("callback attachment sentinel");
        }
    }

    private static final class SynchronousCallbackStage
            extends CompletableFuture<AiResponse> {
        private final AiResponse response;

        private SynchronousCallbackStage(AiResponse response) {
            this.response = response;
        }

        @Override
        public CompletableFuture<AiResponse> whenComplete(
                BiConsumer<? super AiResponse, ? super Throwable> action) {
            action.accept(response, null);
            return this;
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

    private static final class ErroringDeadlineScheduler
            extends ScheduledThreadPoolExecutor {
        private ErroringDeadlineScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            throw new AssertionError("deadline scheduler sentinel");
        }
    }

    private static final class ErroringCancellationDeadlineScheduler
            extends ScheduledThreadPoolExecutor {
        private ErroringCancellationDeadlineScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            return new ErroringCancellationDeadline();
        }
    }

    private static final class ErroringCancellationDeadline
            implements ScheduledFuture<Object> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            throw new AssertionError("deadline cancellation sentinel");
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MAX_VALUE;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }

    private static final class BlockingDeadlineScheduler
            extends ScheduledThreadPoolExecutor {
        private final CountDownLatch scheduleEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSchedule = new CountDownLatch(1);
        private final FirstCancelSucceedsThenThrowsDeadline deadline =
                new FirstCancelSucceedsThenThrowsDeadline();

        private BlockingDeadlineScheduler() {
            super(1);
        }

        @Override
        public ScheduledFuture<?> schedule(
                Runnable command, long delay, TimeUnit unit) {
            scheduleEntered.countDown();
            try {
                if (!releaseSchedule.await(1L, TimeUnit.SECONDS)) {
                    throw new AssertionError("deadline scheduler release timed out");
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new AssertionError("deadline scheduler was interrupted", exception);
            }
            return deadline;
        }
    }

    private static final class FirstCancelSucceedsThenThrowsDeadline
            implements ScheduledFuture<Object> {
        private final AtomicInteger cancelCalls = new AtomicInteger();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            if (cancelCalls.incrementAndGet() > 1) {
                throw new AssertionError("late deadline was cancelled twice");
            }
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelCalls.get() > 0;
        }

        @Override
        public boolean isDone() {
            return cancelCalls.get() > 0;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }

        @Override
        public long getDelay(TimeUnit unit) {
            return Long.MAX_VALUE;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }
    }
}
