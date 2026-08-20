package io.github.greytaiwolf.botplayer.client;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptClientGrantStatus;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiReviewOnlyProposalShape;
import io.github.greytaiwolf.botplayer.client.ai.ClientAiPhysicalAttemptPreparation;
import io.github.greytaiwolf.botplayer.client.ai.ClientAiRequestDispatchStatus;
import io.github.greytaiwolf.botplayer.client.ai.ClientAiRequestSessionController;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientAiProviderFactory;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettingsController;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettingsException;
import io.github.greytaiwolf.botplayer.client.ai.ReviewOnlyClientSettingsStore;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.client.credential.CredentialStoreException;
import io.github.greytaiwolf.botplayer.client.screen.BotInventoryScreen;
import io.github.greytaiwolf.botplayer.inventory.BotPlayerMenus;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptOfferPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptPrepareAckPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiPhysicalAttemptStartGrantPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.RegisterMenuScreensEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Physical-client bootstrap for local credential storage.
 */
@Mod(value = BotPlayer.MOD_ID, dist = Dist.CLIENT)
public final class BotPlayerClient {
    private static ClientCredentialStore credentialStore;
    private static boolean credentialStoreUnavailable;
    /*
     * A factory exists only after the local review-only settings file has explicitly enabled it.
     * No S2C dispatch, credential bind, or server-selected field can install one.
     */
    private static ReviewOnlyClientAiProviderFactory aiProviderFactory;
    private static ReviewOnlyClientSettingsController reviewOnlySettingsController;
    private static boolean reviewOnlySettingsUnavailable;
    private static ClientAiRequestSessionController aiRequestSessions;
    private static ScheduledExecutorService aiDeadlineScheduler;
    /** Atomically replaced for every physical-server connection to poison queued old sends. */
    private static volatile AiClientConnectionEpoch aiConnection =
            AiClientConnectionEpoch.initial();

    public BotPlayerClient(IEventBus modBus) {
        initializeCredentialStore();
        initializeReviewOnlySettings();
        ClientPayloadHandlers.install(new PhysicalClientPayloadHandler());
        modBus.addListener(BotPlayerClient::registerMenuScreens);
    }

    private static void registerMenuScreens(RegisterMenuScreensEvent event) {
        event.register(
                BotPlayerMenus.BOT_INVENTORY.get(), BotInventoryScreen::new);
    }

    public static synchronized Optional<ClientCredentialStore> credentialStore() {
        if (credentialStore == null && !credentialStoreUnavailable) {
            initializeCredentialStore();
        }
        return Optional.ofNullable(credentialStore);
    }

    /**
     * Reloads the one persisted physical-client P6-R1 opt-in.
     *
     * <p>This is intentionally local-only: it reads neither a server packet nor a credential
     * profile field. A reload retires old sessions and its factory before installing the fixed
     * policy again, so stale completions cannot send a C2S proposal.
     */
    public static synchronized boolean reloadReviewOnlyLocalSettings() {
        if (!ensureReviewOnlySettingsController()) {
            return false;
        }
        try {
            reviewOnlySettingsController.reload();
            return true;
        } catch (ReviewOnlyClientSettingsException | RuntimeException exception) {
            failClosedReviewOnlySettings();
            return false;
        }
    }

    /**
     * Persists the local-only R1 opt-in. Nothing on the server can invoke this method through the
     * payload protocol; it is provided for a future physical-client settings surface.
     */
    public static synchronized boolean setReviewOnlyEnabled(boolean enabled) {
        if (!ensureReviewOnlySettingsController()) {
            return false;
        }
        try {
            reviewOnlySettingsController.setEnabled(enabled);
            return true;
        } catch (ReviewOnlyClientSettingsException | RuntimeException exception) {
            failClosedReviewOnlySettings();
            return false;
        }
    }

    /**
     * Stages one registered P6 physical-attempt offer and sends its exact ACK only while that
     * staged local session remains current. Receiving an offer never starts a Provider.
     */
    public static ClientAiRequestDispatchStatus handleAiPhysicalAttemptOffer(
            AiPhysicalAttemptOfferPayload payload, UUID localOwnerId) {
        return handleAiPhysicalAttemptOffer(
                payload, localOwnerId, currentAiConnectionEpoch());
    }

    /**
     * Handles an offer received while {@code expectedConnectionEpoch} was current. The physical
     * network sink captures the epoch before queuing to the Minecraft thread, so an old ingress
     * cannot settle a server reservation after logout/reconnect.
     */
    static ClientAiRequestDispatchStatus handleAiPhysicalAttemptOffer(
            AiPhysicalAttemptOfferPayload payload, UUID localOwnerId,
            long expectedConnectionEpoch) {
        AiPhysicalAttemptOfferPayload checkedPayload = Optional.ofNullable(payload).orElseThrow(
                () -> new NullPointerException("payload"));
        UUID checkedOwnerId = Optional.ofNullable(localOwnerId).orElseThrow(
                () -> new NullPointerException("localOwnerId"));
        if (!checkedPayload.offer().identity().matches(checkedPayload.dispatch())
                || !AiReviewOnlyContract.isCanonicalDispatch(checkedPayload.dispatch())) {
            return ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED;
        }
        ClientAiRequestSessionController controller;
        synchronized (BotPlayerClient.class) {
            AiClientConnectionEpoch connection = aiConnection;
            if (!connection.acceptsIngress(expectedConnectionEpoch)) {
                return ClientAiRequestDispatchStatus.CANCELLED;
            }
            if (credentialStore == null && !credentialStoreUnavailable) {
                initializeCredentialStore();
            }
            if (credentialStore == null
                    || aiProviderFactory == null) {
                return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
            }
            if (!aiProviderFactory.accepts(checkedPayload.dispatch())) {
                return ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED;
            }
            if (aiDeadlineScheduler == null) {
                aiDeadlineScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
                    Thread thread = new Thread(runnable, "BotPlayer-AI-Deadline");
                    thread.setDaemon(true);
                    return thread;
                });
            }
            if (aiRequestSessions == null) {
                long connectionEpoch = connection.epoch();
                aiRequestSessions = new ClientAiRequestSessionController(
                        credentialStore,
                        checkedOwnerId,
                        aiProviderFactory,
                        System::currentTimeMillis,
                        aiDeadlineScheduler,
                        () -> isCurrentAiConnection(connectionEpoch),
                        (dispatch, proposal, bindingEpoch) -> queueAiProposal(
                                connectionEpoch, dispatch, proposal, bindingEpoch));
            }
            controller = aiRequestSessions;
        }
        controller.updateLocalOwner(checkedOwnerId);
        ClientAiPhysicalAttemptPreparation preparation = controller.preparePhysicalAttempt(
                checkedPayload.offer(), checkedPayload.dispatch());
        if (preparation.status() != ClientAiRequestDispatchStatus.PREPARED) {
            return preparation.status();
        }

        boolean acknowledged = false;
        try {
            synchronized (BotPlayerClient.class) {
                if (aiConnection.acceptsIngress(expectedConnectionEpoch)
                        && aiRequestSessions == controller
                        && checkedOwnerId.equals(checkedPayload.dispatch().ownerId())
                        && controller.isCurrentPreparedPhysicalAttempt(
                                preparation.prepareAck().orElseThrow().identity())) {
                    PacketDistributor.sendToServer(new AiPhysicalAttemptPrepareAckPayload(
                            preparation.prepareAck().orElseThrow()));
                    acknowledged = true;
                }
            }
        } catch (RuntimeException exception) {
            /* A failed handoff must not retain a future provider start permission. */
        }
        if (!acknowledged) {
            controller.cancelRequest(checkedPayload.dispatch().requestId());
            return ClientAiRequestDispatchStatus.CANCELLED;
        }
        return ClientAiRequestDispatchStatus.PREPARED;
    }

    /** Delivers one registered grant only to the exact locally staged owner session. */
    public static AiPhysicalAttemptClientGrantStatus handleAiPhysicalAttemptStartGrant(
            AiPhysicalAttemptStartGrantPayload payload, UUID localOwnerId) {
        return handleAiPhysicalAttemptStartGrant(
                payload, localOwnerId, currentAiConnectionEpoch());
    }

    /** Applies a queued grant only while its captured physical connection remains current. */
    static AiPhysicalAttemptClientGrantStatus handleAiPhysicalAttemptStartGrant(
            AiPhysicalAttemptStartGrantPayload payload, UUID localOwnerId,
            long expectedConnectionEpoch) {
        AiPhysicalAttemptStartGrantPayload checkedPayload = Optional.ofNullable(payload)
                .orElseThrow(() -> new NullPointerException("payload"));
        UUID checkedOwnerId = Optional.ofNullable(localOwnerId)
                .orElseThrow(() -> new NullPointerException("localOwnerId"));
        if (!checkedOwnerId.equals(checkedPayload.startGrant().identity().ownerId())) {
            return AiPhysicalAttemptClientGrantStatus.LOCAL_SESSION_INACTIVE;
        }
        ClientAiRequestSessionController controller;
        synchronized (BotPlayerClient.class) {
            if (!aiConnection.acceptsIngress(expectedConnectionEpoch)
                    || aiRequestSessions == null) {
                return AiPhysicalAttemptClientGrantStatus.LOCAL_SESSION_INACTIVE;
            }
            controller = aiRequestSessions;
        }
        return controller.acceptPhysicalAttemptStartGrant(checkedPayload.startGrant());
    }

    /**
     * Physical-client endpoint for a server lifecycle cancellation. The controller performs the
     * complete correlation comparison; this facade additionally keeps an old connection epoch
     * from cancelling a session after disconnect/reconnect.
     */
    public static boolean handleAiRequestCancellation(
            AiRequestCancellationPayload payload, UUID localOwnerId) {
        return handleAiRequestCancellation(
                payload, localOwnerId, currentAiConnectionEpoch());
    }

    /** Applies a cancellation only if it belongs to the ingress connection epoch. */
    static boolean handleAiRequestCancellation(
            AiRequestCancellationPayload payload, UUID localOwnerId,
            long expectedConnectionEpoch) {
        AiRequestCancellationPayload checkedPayload = Optional.ofNullable(payload).orElseThrow(
                () -> new NullPointerException("payload"));
        UUID checkedOwnerId = Optional.ofNullable(localOwnerId).orElseThrow(
                () -> new NullPointerException("localOwnerId"));
        synchronized (BotPlayerClient.class) {
            if (!aiConnection.acceptsIngress(expectedConnectionEpoch)
                    || aiRequestSessions == null
                    || !checkedOwnerId.equals(checkedPayload.ownerId())) {
                return false;
            }
            return aiRequestSessions.cancel(checkedPayload);
        }
    }

    /** Stops local sessions when the physical client leaves its current server connection. */
    public static synchronized void clearAiRequestSessions() {
        aiConnection = aiConnection.nextDisconnected();
        if (aiRequestSessions != null) {
            aiRequestSessions.clearLocalOwner();
            aiRequestSessions = null;
        }
    }

    static synchronized void beginAiClientConnection() {
        /* Advance first so callbacks retained by the previous connection fail immediately. */
        aiConnection = aiConnection.nextConnected();
        if (aiRequestSessions != null) {
            aiRequestSessions.clearLocalOwner();
            aiRequestSessions = null;
        }
    }

    /**
     * Captures the immutable physical connection epoch at network ingress.
     * An inactive connection uses {@code -1}, which cannot match a live
     * connection and therefore fails closed after a queued handoff.
     */
    static long currentAiConnectionEpoch() {
        return aiConnection.ingressEpoch();
    }

    /**
     * Cancels a local request before this client's credential binding for the bot is changed or
     * removed. No C2S cancellation payload is emitted because the server proposal gate independently
     * expires or closes the correlation.
     */
    public static synchronized void cancelAiRequestForBot(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        if (aiRequestSessions != null) {
            /*
             * Advance before cancellation: a completed session may already have handed a
             * proposal to Minecraft's queue and therefore no longer appear in sessionsByBot.
             * Its captured per-bot lease still observes this change at the physical C2S send.
             */
            aiRequestSessions.advanceBindingEpoch(botId);
            aiRequestSessions.cancelBot(botId);
        }
    }

    private static void queueAiProposal(
            long connectionEpoch,
            AiClientRequestDispatch dispatch,
            AiProposalPayload proposal,
            ClientAiRequestSessionController.BindingEpochHandoff bindingEpoch) {
        try {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> {
                try {
                    /*
                     * Keep the epoch check and send in the same monitor used by disconnect/factory
                     * replacement. A logout that wins this race changes the immutable epoch first;
                     * a send that wins was still on the live physical connection at handoff time.
                     */
                    synchronized (BotPlayerClient.class) {
                        AiClientConnectionEpoch connection = aiConnection;
                        if (!connection.acceptsIngress(connectionEpoch)
                                || !bindingEpoch.isCurrentFor(dispatch.botId())
                                || System.currentTimeMillis() >= dispatch.expiresAtEpochMillis()
                                || !AiReviewOnlyContract.isCanonicalDispatch(dispatch)
                                || !isCanonicalReviewProposal(proposal)
                                || !proposalMatchesDispatch(dispatch, proposal)
                                || !dispatch.options().toolCallsAllowed()
                                || proposal.encodedByteLength()
                                        > AiProposalPayload.MAX_ENCODED_FRAME_BYTES) {
                            return;
                        }
                        LocalPlayer player = minecraft.player;
                        if (player == null || !player.getUUID().equals(dispatch.ownerId())) {
                            return;
                        }
                        if (!hasMatchingLocalBinding(dispatch)) {
                            return;
                        }

                        /*
                         * The server is still the authority for the current lifecycle generation. The
                         * client verifies the immutable generation/correlation it received and never
                         * sends a queued proposal after a connection epoch, local binding epoch,
                         * owner, agent, or wall-clock TTL change.
                         */
                        try {
                            PacketDistributor.sendToServer(proposal);
                        } catch (RuntimeException exception) {
                            /*
                             * A transport implementation may still reject a packet for a framing rule
                             * outside this codec. Never let that turn untrusted model output into a client
                             * crash or a retry with the same correlation.
                             */
                        }
                    }
                } finally {
                    bindingEpoch.release();
                }
            });
        } catch (RuntimeException exception) {
            /* A rejected Minecraft-thread handoff retains no binding-epoch lease. */
            bindingEpoch.release();
        }
    }

    private static boolean proposalMatchesDispatch(
            AiClientRequestDispatch dispatch,
            AiProposalPayload proposal) {
        return dispatch.botId().equals(proposal.botId())
                && dispatch.agentId().equals(proposal.agentId())
                && dispatch.generation() == proposal.generation()
                && dispatch.requestId().equals(proposal.requestId())
                && dispatch.nonce().equals(proposal.nonce())
                && dispatch.revision() == proposal.revision();
    }

    /** The physical client never forwards model prose or a second/arbitrary review tool call. */
    private static boolean isCanonicalReviewProposal(AiProposalPayload proposal) {
        return AiReviewOnlyProposalShape.matches(proposal);
    }

    private static boolean hasMatchingLocalBinding(
            AiClientRequestDispatch dispatch) {
        if (credentialStore == null) {
            return false;
        }
        try {
            return credentialStore.findBinding(
                    dispatch.serverInstanceId(), dispatch.ownerId(), dispatch.botId())
                    .filter(binding -> dispatch.agentId().equals(binding.agentId()))
                    .isPresent();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private static boolean isCurrentAiConnection(long connectionEpoch) {
        return aiConnection.acceptsIngress(connectionEpoch);
    }

    /** Installs only a factory produced by the local fixed-policy settings controller. */
    private static synchronized void installReviewOnlyAiProviderFactory(
            ReviewOnlyClientAiProviderFactory providerFactory) {
        ReviewOnlyClientAiProviderFactory checkedFactory = Objects.requireNonNull(
                providerFactory, "providerFactory");
        clearReviewOnlyRuntime();
        aiProviderFactory = checkedFactory;
    }

    /**
     * Retires all locally credential-capable state for a local policy change.
     *
     * <p>The connection epoch advances even when no session exists. Thus a queued old S2C
     * callback cannot resurrect a Provider after disable or reload; cancellation stays entirely
     * client-local and never emits a C2S control packet.
     */
    private static synchronized void clearReviewOnlyRuntime() {
        aiConnection = aiConnection.nextForLocalFactoryChange();
        if (aiRequestSessions != null) {
            aiRequestSessions.cancelAll();
            aiRequestSessions = null;
        }
        if (aiDeadlineScheduler != null) {
            try {
                aiDeadlineScheduler.shutdownNow();
            } catch (RuntimeException ignored) {
                // Provider/session state is still cleared below if an executor rejects shutdown.
            } finally {
                aiDeadlineScheduler = null;
            }
        }
        aiProviderFactory = null;
    }

    private static synchronized void initializeReviewOnlySettings() {
        if (reviewOnlySettingsController != null || reviewOnlySettingsUnavailable) {
            return;
        }
        Path directory = FMLPaths.CONFIGDIR.get().resolve(BotPlayer.MOD_ID);
        try {
            ReviewOnlyClientSettingsStore settingsStore =
                    new ReviewOnlyClientSettingsStore(directory);
            reviewOnlySettingsController = new ReviewOnlyClientSettingsController(
                    settingsStore,
                    ReviewOnlyClientSettingsController::createFixedFactory,
                    new ReviewOnlyClientSettingsController.Runtime() {
                        @Override
                        public void install(ReviewOnlyClientAiProviderFactory factory) {
                            installReviewOnlyAiProviderFactory(factory);
                        }

                        @Override
                        public void disable() {
                            clearReviewOnlyRuntime();
                        }
                    });
            reviewOnlySettingsController.reload();
        } catch (ReviewOnlyClientSettingsException | RuntimeException exception) {
            failClosedReviewOnlySettings();
        }
    }

    /** Allows an explicit later local reload after a user repairs a malformed local config file. */
    private static synchronized boolean ensureReviewOnlySettingsController() {
        if (reviewOnlySettingsController == null && reviewOnlySettingsUnavailable) {
            reviewOnlySettingsUnavailable = false;
        }
        initializeReviewOnlySettings();
        return reviewOnlySettingsController != null;
    }

    private static synchronized void failClosedReviewOnlySettings() {
        reviewOnlySettingsController = null;
        reviewOnlySettingsUnavailable = true;
        clearReviewOnlyRuntime();
        /* Do not attach exception text: filesystem content is local input and not a diagnostic API. */
        BotPlayer.LOGGER.error(
                "BotPlayer local review-only AI settings are unavailable; refusing to enable a Provider");
    }

    private static synchronized void initializeCredentialStore() {
        if (credentialStore != null || credentialStoreUnavailable) {
            return;
        }

        Path directory = FMLPaths.CONFIGDIR.get().resolve(BotPlayer.MOD_ID);
        try {
            credentialStore = new ClientCredentialStore(directory);
        } catch (CredentialStoreException exception) {
            credentialStoreUnavailable = true;
            // Do not attach the exception: malformed local content may include credential text.
            BotPlayer.LOGGER.error(
                    "BotPlayer local credential storage is unavailable; refusing to load or overwrite it");
        }
    }
}
