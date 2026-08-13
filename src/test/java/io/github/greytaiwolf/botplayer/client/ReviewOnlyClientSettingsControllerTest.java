package io.github.greytaiwolf.botplayer.client;

import io.github.greytaiwolf.botplayer.ai.AiCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiCapability;
import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiTokenUsage;
import io.github.greytaiwolf.botplayer.ai.CancellationToken;
import io.github.greytaiwolf.botplayer.ai.ProviderHealth;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlySnapshotProjection;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.github.greytaiwolf.botplayer.client.ai.ClientAiRequestDispatchStatus;
import io.github.greytaiwolf.botplayer.client.ai.ClientAiRequestSessionController;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientAiProviderFactory;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettings;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettingsController;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettingsException;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettingsStore;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyDeepSeekClientAiProviderFactory;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReviewOnlyClientSettingsControllerTest {
    private static final UUID SERVER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000001");
    private static final UUID BOT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000101");
    private static final UUID OWNER_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000151");
    private static final UUID AGENT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000201");
    private static final UUID REQUEST_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000301");

    @TempDir
    Path temporaryDirectory;

    private ScheduledExecutorService deadlineScheduler;

    @AfterEach
    void tearDown() {
        if (deadlineScheduler != null) {
            deadlineScheduler.shutdownNow();
        }
    }

    @Test
    void disabledDefaultPersistsOnlyTheOptInAndCredentialBindingCannotConstructAProvider()
            throws Exception {
        ReviewOnlyClientSettingsStore settingsStore = new ReviewOnlyClientSettingsStore(
                temporaryDirectory.resolve("config"));
        ControlledProvider provider = new ControlledProvider();
        RecordingRuntime runtime = new RecordingRuntime();
        AtomicInteger factoryCreations = new AtomicInteger();
        ReviewOnlyClientSettingsController controller = new ReviewOnlyClientSettingsController(
                settingsStore,
                () -> {
                    factoryCreations.incrementAndGet();
                    return new FixedTestFactory(provider);
                },
                runtime);

        Assertions.assertEquals(ReviewOnlyClientSettings.DEFAULT, controller.reload());
        Assertions.assertEquals(0, factoryCreations.get());
        Assertions.assertTrue(runtime.factory == null);

        try (ClientCredentialStore credentialStore = new ClientCredentialStore(
                temporaryDirectory.resolve("config"))) {
            credentialStore.bind(
                    SERVER_ID,
                    OWNER_ID,
                    BOT_ID,
                    ClientCredentialStore.DEFAULT_PROFILE_ID,
                    "fake-key-1234",
                    Optional.of(AGENT_ID));
        }

        /* A fully canonical server-shaped dispatch is only data; it cannot flip local opt-in. */
        AiClientRequestDispatch dispatch = reviewDispatch();
        Assertions.assertTrue(AiReviewOnlyContract.isCanonicalDispatch(dispatch));
        Assertions.assertEquals(0, factoryCreations.get());
        Assertions.assertTrue(runtime.factory == null);

        String persisted = Files.readString(settingsStore.path(), StandardCharsets.UTF_8);
        Assertions.assertEquals(
                """
                {
                  "schemaVersion": 1,
                  "reviewOnly": {
                    "enabled": false
                  }
                }
                """.strip(),
                persisted.strip(),
                "the persisted schema must contain only the fail-closed opt-in bit");
        Assertions.assertFalse(persisted.contains("endpoint"));
        Assertions.assertFalse(persisted.contains("model"));
        Assertions.assertFalse(persisted.contains("provider"));
        Assertions.assertFalse(persisted.contains("profile"));
        Assertions.assertFalse(persisted.contains("tool"));
        Assertions.assertFalse(persisted.contains("prompt"));
        Assertions.assertFalse(persisted.contains("fake-key"));
    }

    @Test
    void explicitEnableConstructsOnlyTheFixedDeepSeekChatToolPolicy() throws Exception {
        AiModelCapabilities fixed = ReviewOnlyClientSettingsController.fixedModelCapabilities();
        Assertions.assertEquals(AiReviewOnlyContract.MODEL, fixed.model());
        Assertions.assertTrue(fixed.maximumOutputTokens()
                >= AiReviewOnlyContract.MAXIMUM_OUTPUT_TOKENS);
        Assertions.assertEquals(Set.of(AiCapability.CHAT, AiCapability.TOOL_CALLS),
                fixed.capabilities());

        RecordingRuntime runtime = new RecordingRuntime();
        ReviewOnlyClientSettingsController controller = new ReviewOnlyClientSettingsController(
                new ReviewOnlyClientSettingsStore(temporaryDirectory.resolve("config")),
                ReviewOnlyClientSettingsController::createFixedFactory,
                runtime);
        Assertions.assertTrue(controller.setEnabled(true).enabled());
        ReviewOnlyDeepSeekClientAiProviderFactory factory = Assertions.assertInstanceOf(
                ReviewOnlyDeepSeekClientAiProviderFactory.class, runtime.factory);
        Assertions.assertTrue(factory.accepts(reviewDispatch()));
        Assertions.assertFalse(factory.accepts(dispatch(AiRequestPurpose.UNSPECIFIED_V1)));
    }

    @Test
    void reloadingAndDisablingCancelLocalWorkClearFactoryAndNeverEmitAProposal()
            throws Exception {
        ReviewOnlyClientSettingsStore settingsStore = new ReviewOnlyClientSettingsStore(
                temporaryDirectory.resolve("config"));
        ControlledProvider provider = new ControlledProvider();
        RecordingRuntime runtime = new RecordingRuntime();
        ReviewOnlyClientSettingsController settings = new ReviewOnlyClientSettingsController(
                settingsStore,
                () -> new FixedTestFactory(provider),
                runtime);
        Assertions.assertTrue(settings.setEnabled(true).enabled());
        Assertions.assertNotNull(runtime.factory);
        ReviewOnlyClientAiProviderFactory initialFactory = runtime.factory;

        deadlineScheduler = Executors.newSingleThreadScheduledExecutor();
        List<AiProposalPayload> c2sProposals = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000L);
        AiClientRequestSessionController sessions;
        try (ClientCredentialStore credentialStore = new ClientCredentialStore(
                temporaryDirectory.resolve("config"))) {
            credentialStore.bind(
                    SERVER_ID,
                    OWNER_ID,
                    BOT_ID,
                    ClientCredentialStore.DEFAULT_PROFILE_ID,
                    "fake-key-1234",
                    Optional.of(AGENT_ID));
            sessions = new ClientAiRequestSessionController(
                    credentialStore,
                    OWNER_ID,
                    runtime.factory,
                    clock::get,
                    deadlineScheduler,
                    (dispatch, proposal) -> c2sProposals.add(proposal));
            runtime.sessions = sessions;

            AiClientRequestDispatch dispatch = reviewDispatch();
            Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                    sessions.accept(dispatch));
            Assertions.assertEquals(1, provider.calls.get());

            long epochBeforeReload = runtime.connection.epoch();
            long ingressBeforeReload = runtime.connection.ingressEpoch();
            Assertions.assertTrue(settings.reload().enabled());
            Assertions.assertTrue(runtime.connection.epoch() > epochBeforeReload);
            Assertions.assertFalse(runtime.connection.acceptsIngress(ingressBeforeReload));
            Assertions.assertNotSame(initialFactory, runtime.factory);
            Assertions.assertTrue(runtime.sessions == null);
            Assertions.assertTrue(provider.token.get().isCancellationRequested());

            provider.completion.complete(toolResponse(dispatch));
            Assertions.assertTrue(c2sProposals.isEmpty(),
                    "a local reload must not emit a C2S proposal");

            long epochBeforeDisable = runtime.connection.epoch();
            long ingressBeforeDisable = runtime.connection.ingressEpoch();
            Assertions.assertFalse(settings.setEnabled(false).enabled());
            Assertions.assertTrue(runtime.connection.epoch() > epochBeforeDisable);
            Assertions.assertFalse(runtime.connection.acceptsIngress(ingressBeforeDisable));
            Assertions.assertTrue(runtime.factory == null);
            Assertions.assertTrue(runtime.sessions == null);

            long epochBeforeDisabledReload = runtime.connection.epoch();
            Assertions.assertFalse(settings.reload().enabled());
            Assertions.assertTrue(runtime.connection.epoch() > epochBeforeDisabledReload);
            Assertions.assertTrue(runtime.factory == null);
        }
    }

    @Test
    void corruptReloadCancelsEnabledSessionAndRejectsUnknownLocalFieldsWithoutC2S()
            throws Exception {
        ReviewOnlyClientSettingsStore settingsStore = new ReviewOnlyClientSettingsStore(
                temporaryDirectory.resolve("config"));
        ControlledProvider provider = new ControlledProvider();
        RecordingRuntime runtime = new RecordingRuntime();
        ReviewOnlyClientSettingsController controller = new ReviewOnlyClientSettingsController(
                settingsStore,
                () -> new FixedTestFactory(provider),
                runtime);
        controller.setEnabled(true);
        Assertions.assertNotNull(runtime.factory);

        deadlineScheduler = Executors.newSingleThreadScheduledExecutor();
        List<AiProposalPayload> c2sProposals = new ArrayList<>();
        AtomicLong clock = new AtomicLong(1_000L);
        try (ClientCredentialStore credentialStore = new ClientCredentialStore(
                temporaryDirectory.resolve("config"))) {
            credentialStore.bind(
                    SERVER_ID,
                    OWNER_ID,
                    BOT_ID,
                    ClientCredentialStore.DEFAULT_PROFILE_ID,
                    "fake-key-1234",
                    Optional.of(AGENT_ID));
            ClientAiRequestSessionController sessions = new ClientAiRequestSessionController(
                    credentialStore,
                    OWNER_ID,
                    runtime.factory,
                    clock::get,
                    deadlineScheduler,
                    (dispatch, proposal) -> c2sProposals.add(proposal));
            runtime.sessions = sessions;
            AiClientRequestDispatch dispatch = reviewDispatch();
            Assertions.assertEquals(ClientAiRequestDispatchStatus.STARTED,
                    sessions.accept(dispatch));
            Assertions.assertEquals(1, provider.calls.get());

            Files.writeString(
                    settingsStore.path(),
                    "{\"schemaVersion\":1,\"reviewOnly\":{\"enabled\":false},\"endpoint\":\"https://example.invalid\"}",
                    StandardCharsets.UTF_8);
            long epochBeforeReload = runtime.connection.epoch();
            ReviewOnlyClientSettingsException exception = Assertions.assertThrows(
                    ReviewOnlyClientSettingsException.class,
                    controller::reload);
            Assertions.assertEquals(
                    ReviewOnlyClientSettingsException.Reason.CORRUPT_OR_UNSUPPORTED,
                    exception.reason());
            Assertions.assertTrue(runtime.connection.epoch() > epochBeforeReload);
            Assertions.assertTrue(runtime.factory == null);
            Assertions.assertTrue(runtime.sessions == null);
            Assertions.assertTrue(provider.token.get().isCancellationRequested());

            provider.completion.complete(toolResponse(dispatch));
            Assertions.assertTrue(c2sProposals.isEmpty(),
                    "a corrupt local reload must not emit a C2S proposal");
        }
    }

    private static AiClientRequestDispatch reviewDispatch() {
        return dispatch(AiRequestPurpose.REVIEW_ONLY_V1);
    }

    private static AiClientRequestDispatch dispatch(AiRequestPurpose purpose) {
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
        return new AiClientRequestDispatch(
                SERVER_ID,
                BOT_ID,
                OWNER_ID,
                AGENT_ID,
                2L,
                REQUEST_ID,
                UUID.fromString("00000000-0000-0000-0000-000000000401"),
                projection.snapshotId(),
                purpose,
                100L,
                140L,
                1_000L,
                3_000L,
                AiReviewOnlyContract.PROVIDER_ID,
                AiReviewOnlyContract.MODEL,
                AiReviewOnlyContract.requestTemplate(projection).messages(),
                AiReviewOnlyContract.requestTemplate(projection).options(),
                Optional.empty());
    }

    private static AiResponse toolResponse(AiClientRequestDispatch dispatch) {
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

    private static final class RecordingRuntime
            implements ReviewOnlyClientSettingsController.Runtime {
        private AiClientConnectionEpoch connection = AiClientConnectionEpoch.initial()
                .nextConnected();
        private ReviewOnlyClientAiProviderFactory factory;
        private ClientAiRequestSessionController sessions;

        @Override
        public void install(ReviewOnlyClientAiProviderFactory factory) {
            retire();
            this.factory = factory;
        }

        @Override
        public void disable() {
            retire();
        }

        private void retire() {
            connection = connection.nextForLocalFactoryChange();
            if (sessions != null) {
                sessions.cancelAll();
                sessions = null;
            }
            factory = null;
        }
    }

    private static final class FixedTestFactory
            implements ReviewOnlyClientAiProviderFactory {
        private final ControlledProvider provider;

        private FixedTestFactory(ControlledProvider provider) {
            this.provider = provider;
        }

        @Override
        public boolean accepts(AiClientRequestDispatch dispatch) {
            return AiReviewOnlyContract.isCanonicalDispatch(dispatch);
        }

        @Override
        public AiProvider create(
                AiClientRequestDispatch dispatch, ClientCredentialStore credentialStore) {
            return provider;
        }
    }

    private static final class ControlledProvider implements AiProvider {
        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicReference<CancellationToken> token = new AtomicReference<>();
        private final CompletableFuture<AiResponse> completion = new CompletableFuture<>();

        @Override
        public CompletionStage<AiResponse> complete(
                io.github.greytaiwolf.botplayer.ai.AiRequest request,
                CancellationToken token) {
            calls.incrementAndGet();
            this.token.set(token);
            return completion;
        }

        @Override
        public CompletionStage<AiCapabilities> probeCapabilities() {
            return CompletableFuture.failedFuture(
                    new UnsupportedOperationException("not used in this test"));
        }

        @Override
        public ProviderHealth health() {
            return ProviderHealth.unknown("test", Instant.EPOCH);
        }
    }
}
