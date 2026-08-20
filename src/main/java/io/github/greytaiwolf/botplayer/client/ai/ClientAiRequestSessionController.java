package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptClientGrantGate;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptClientGrantStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOffer;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptStartGrant;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.CancellationTokenSource;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientSponsoredTerminalObservation;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientSponsoredTerminalStatus;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestPurpose;
import io.github.greytaiwolf.botplayer.client.credential.BotCredentialBinding;
import io.github.greytaiwolf.botplayer.client.credential.ClientCredentialStore;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalToolCallPayload;
import io.github.greytaiwolf.botplayer.network.payload.AiRequestCancellationPayload;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Client-local lifecycle for one owner-sponsored AI request at a time per bot.
 *
 * <p>This class contains no Minecraft world object and performs no world action. It accepts an
 * already decoded server dispatch, verifies the exact local owner and
 * {@code (serverInstanceId, ownerId, botId)} credential binding, and invokes an injected local
 * {@link AiProvider} asynchronously. A completed response is allowed to leave the client only as
 * the existing bounded {@link AiProposalPayload}; it is never parsed into a SkillPlan or executed
 * locally.
 *
 * <p>Provider callbacks may run on arbitrary executors. The proposal sink therefore must be a
 * non-blocking handoff to the physical client thread; it is also called only after all local
 * correlation checks still hold. The server remains the final authority and rechecks every field
 * in the C2S payload.
 */
public final class ClientAiRequestSessionController {
    /**
     * A dispatch whose server issue time is materially in the local future is rejected instead of
     * turning a clock-skewed or replayed packet into a long-lived local HTTP request.
     */
    static final long MAX_CLOCK_SKEW_MILLIS = 5_000L;
    /**
     * Terminal ids remain reserved until their server-issued epoch expiry. Refuse new work when
     * full instead of evicting a live tombstone and reopening a replay window.
     */
    static final int MAX_REQUEST_TOMBSTONES = 256;
    private static final ClientAiRequestTerminalObserver NOOP_TERMINAL_OBSERVER =
            observation -> {};

    /**
     * Physical-client handoff for a completed proposal.
     *
     * <p>An asynchronous handoff must call {@link BindingEpochHandoff#release()} exactly once
     * after it sends or drops the proposal. The lease remains valid only while the same bot's
     * local credential binding epoch is unchanged. The handoff is invoked while this controller
     * serializes the final cancellation-before-queue check, so it must not synchronously call a
     * controller public API. A synchronous completion of another controller-owned stage is
     * detected and failed closed: both local sessions are structurally detached under the lock,
     * their token/terminal cleanup runs after it is released, and the outer handoff's lease is
     * invalidated. A handoff must still queue rather than transmit a payload before it returns,
     * because a payload transmitted before its lease is checked cannot be recalled.
     */
    @FunctionalInterface
    public interface ProposalHandoff {
        void accept(
                AiClientRequestDispatch dispatch,
                AiProposalPayload proposal,
                BindingEpochHandoff bindingEpoch);
    }

    /** Per-completion binding guard retained until the physical-client queue finishes it. */
    public interface BindingEpochHandoff {
        /** Returns true only for the same bot while its local binding epoch is still current. */
        boolean isCurrentFor(UUID botId);

        /** Idempotently retires this queued handoff. */
        void release();
    }

    private final Object lock = new Object();
    private final ClientCredentialStore credentialStore;
    private final ClientAiProviderFactory providerFactory;
    private final LongSupplier currentEpochMillis;
    private final ScheduledExecutorService deadlineScheduler;
    private final BooleanSupplier sessionActive;
    private final ProposalHandoff proposalHandoff;
    private final ClientAiRequestTerminalObserver terminalObserver;
    private final Map<UUID, ActiveSession> sessionsByRequest = new LinkedHashMap<>();
    private final Map<UUID, ActiveSession> sessionsByBot = new LinkedHashMap<>();
    private final Map<UUID, Long> tombstoneExpiryByRequest = new LinkedHashMap<>();
    /* Entries exist only while a session or queued physical-client handoff retains them. */
    private final Map<UUID, BotBindingEpoch> bindingEpochsByBot = new LinkedHashMap<>();

    /* Guarded by lock; non-null only while ProposalHandoff.accept is executing. */
    private ProposalHandoffScope activeProposalHandoff;

    private UUID localOwnerId;

    public ClientAiRequestSessionController(
            ClientCredentialStore credentialStore,
            UUID localOwnerId,
            ClientAiProviderFactory providerFactory,
            LongSupplier currentEpochMillis,
            ScheduledExecutorService deadlineScheduler,
            BiConsumer<AiClientRequestDispatch, AiProposalPayload> proposalSink) {
        this(
                credentialStore,
                localOwnerId,
                providerFactory,
                currentEpochMillis,
                deadlineScheduler,
                () -> true,
                proposalSink);
    }

    /** Creates an always-active controller with an optional safe terminal-observation handoff. */
    public ClientAiRequestSessionController(
            ClientCredentialStore credentialStore,
            UUID localOwnerId,
            ClientAiProviderFactory providerFactory,
            LongSupplier currentEpochMillis,
            ScheduledExecutorService deadlineScheduler,
            BiConsumer<AiClientRequestDispatch, AiProposalPayload> proposalSink,
            ClientAiRequestTerminalObserver terminalObserver) {
        this(
                credentialStore,
                localOwnerId,
                providerFactory,
                currentEpochMillis,
                deadlineScheduler,
                () -> true,
                proposalSink,
                terminalObserver);
    }

    /**
     * Creates a session controller with a physical-client connection guard.
     *
     * <p>If the guard becomes false, no new provider invocation or proposal handoff is allowed,
     * even if a stale client-network callback still holds this controller after disconnect.
     */
    public ClientAiRequestSessionController(
            ClientCredentialStore credentialStore,
            UUID localOwnerId,
            ClientAiProviderFactory providerFactory,
            LongSupplier currentEpochMillis,
            ScheduledExecutorService deadlineScheduler,
            BooleanSupplier sessionActive,
            BiConsumer<AiClientRequestDispatch, AiProposalPayload> proposalSink) {
        this(
                credentialStore,
                localOwnerId,
                providerFactory,
                currentEpochMillis,
                deadlineScheduler,
                sessionActive,
                synchronousProposalHandoff(proposalSink),
                NOOP_TERMINAL_OBSERVER);
    }

    /**
     * Creates a controller with an optional safe terminal-observation handoff.
     *
     * <p>The observer is invoked only after a locally started session becomes terminal. It is not
     * a packet sender and does not grant it any authority over this controller or the server gate.
     */
    public ClientAiRequestSessionController(
            ClientCredentialStore credentialStore,
            UUID localOwnerId,
            ClientAiProviderFactory providerFactory,
            LongSupplier currentEpochMillis,
            ScheduledExecutorService deadlineScheduler,
            BooleanSupplier sessionActive,
            BiConsumer<AiClientRequestDispatch, AiProposalPayload> proposalSink,
            ClientAiRequestTerminalObserver terminalObserver) {
        this(
                credentialStore,
                localOwnerId,
                providerFactory,
                currentEpochMillis,
                deadlineScheduler,
                sessionActive,
                synchronousProposalHandoff(proposalSink),
                terminalObserver);
    }

    /**
     * Creates a session controller whose completed proposal is handed to the physical client.
     *
     * <p>The handoff receives the exact per-bot binding epoch captured with the dispatch. This
     * permits a queued Minecraft-thread send to reject a response after a local rebind even when
     * the old session already detached from this controller.
     */
    public ClientAiRequestSessionController(
            ClientCredentialStore credentialStore,
            UUID localOwnerId,
            ClientAiProviderFactory providerFactory,
            LongSupplier currentEpochMillis,
            ScheduledExecutorService deadlineScheduler,
            BooleanSupplier sessionActive,
            ProposalHandoff proposalHandoff) {
        this(
                credentialStore,
                localOwnerId,
                providerFactory,
                currentEpochMillis,
                deadlineScheduler,
                sessionActive,
                proposalHandoff,
                NOOP_TERMINAL_OBSERVER);
    }

    /**
     * Creates a controller with an exact physical-client proposal handoff and safe terminal
     * observation hook.
     *
     * <p>When {@code proposalHandoff} honors its no-reentrancy contract, the terminal observer
     * runs outside this controller's lock. Runtime exceptions from it are isolated; an {@link
     * Error} is rethrown only after local cancellation and terminal cleanup have been attempted.
     * The observer cannot send a payload, close a server session, or expose provider data through
     * this API.
     */
    public ClientAiRequestSessionController(
            ClientCredentialStore credentialStore,
            UUID localOwnerId,
            ClientAiProviderFactory providerFactory,
            LongSupplier currentEpochMillis,
            ScheduledExecutorService deadlineScheduler,
            BooleanSupplier sessionActive,
            ProposalHandoff proposalHandoff,
            ClientAiRequestTerminalObserver terminalObserver) {
        this.credentialStore = Objects.requireNonNull(
                credentialStore, "credentialStore");
        this.localOwnerId = requireNonZero(localOwnerId, "localOwnerId");
        this.providerFactory = Objects.requireNonNull(providerFactory, "providerFactory");
        this.currentEpochMillis = Objects.requireNonNull(
                currentEpochMillis, "currentEpochMillis");
        this.deadlineScheduler = Objects.requireNonNull(
                deadlineScheduler, "deadlineScheduler");
        this.sessionActive = Objects.requireNonNull(sessionActive, "sessionActive");
        this.proposalHandoff = Objects.requireNonNull(proposalHandoff, "proposalHandoff");
        this.terminalObserver = Objects.requireNonNull(
                terminalObserver, "terminalObserver");
    }

    /**
     * Rebinds this controller to a newly connected local owner and cancels every prior session.
     */
    public void updateLocalOwner(UUID ownerId) {
        rejectProposalHandoffReentrancy();
        UUID checkedOwnerId = requireNonZero(ownerId, "ownerId");
        List<ActiveSession> cancelled;
        synchronized (lock) {
            if (checkedOwnerId.equals(localOwnerId)) {
                return;
            }
            localOwnerId = checkedOwnerId;
            cancelled = detachAllLocked();
            tombstoneExpiryByRequest.clear();
        }
        cancelAll(cancelled);
    }

    /** Clears the connected owner and prevents all existing sessions from returning a proposal. */
    public void clearLocalOwner() {
        rejectProposalHandoffReentrancy();
        List<ActiveSession> cancelled;
        synchronized (lock) {
            localOwnerId = null;
            cancelled = detachAllLocked();
            tombstoneExpiryByRequest.clear();
        }
        cancelAll(cancelled);
    }

    /**
     * Starts one non-R1 local Provider request only after exact local binding checks.
     *
     * <p>Canonical {@code REVIEW_ONLY_V1} is deliberately rejected here: its production route
     * must use {@link #preparePhysicalAttempt(AiPhysicalAttemptOffer,
     * AiClientRequestDispatch)} and receive an exact server start grant before it can invoke a
     * Provider. Duplicate request ids never execute twice. A newer request for the same bot
     * retires and cancels the older local request before the newer Provider call begins.
     */
    public ClientAiRequestDispatchStatus accept(AiClientRequestDispatch dispatch) {
        rejectProposalHandoffReentrancy();
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        if (checked.purpose() == AiRequestPurpose.REVIEW_ONLY_V1) {
            return AiReviewOnlyContract.isCanonicalDispatch(checked)
                    ? ClientAiRequestDispatchStatus.PHYSICAL_GRANT_REQUIRED
                    : ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED;
        }
        long now = readCurrentEpochMillisSafely();
        if (now < 0L) {
            return ClientAiRequestDispatchStatus.EXPIRED;
        }
        expireThrough(now);
        List<ActiveSession> cancelled = new ArrayList<>();
        ActiveSession session;
        synchronized (lock) {
            ClientAiRequestDispatchStatus localStatus = localStatusLocked(checked, now);
            if (localStatus != ClientAiRequestDispatchStatus.STARTED) {
                return localStatus;
            }
            if (sessionsByRequest.containsKey(checked.requestId())
                    || tombstoneExpiryByRequest.containsKey(checked.requestId())) {
                return ClientAiRequestDispatchStatus.DUPLICATE_REQUEST;
            }
            if (tombstoneExpiryByRequest.size() + sessionsByRequest.size()
                    >= MAX_REQUEST_TOMBSTONES) {
                return ClientAiRequestDispatchStatus.TOMBSTONE_CAPACITY;
            }
            ActiveSession previous = sessionsByBot.get(checked.botId());
            if (previous != null) {
                detachLocked(previous);
                cancelled.add(previous);
            }
            BotBindingEpoch bindingEpoch = bindingEpochForLocked(checked.botId());
            bindingEpoch.activeSessions++;
            session = new ActiveSession(checked, bindingEpoch, bindingEpoch.value);
            sessionsByRequest.put(checked.requestId(), session);
            sessionsByBot.put(checked.botId(), session);
        }
        try {
            cancelAll(cancelled);
        } catch (Throwable cancellationFailure) {
            failReplacementAfterCancellationFailure(session, cancellationFailure);
        }

        /*
         * A local clock may be behind the server's issue timestamp by the tolerated skew. Never
         * turn that skew into extra credential use after the server's tick gate: from receipt,
         * cap the timer by the dispatch's own tick-derived epoch duration as well.
         */
        long dispatchTtlMillis = checked.expiresAtEpochMillis()
                - checked.issuedAtEpochMillis();
        long delayMillis = Math.min(
                checked.expiresAtEpochMillis() - now,
                dispatchTtlMillis);
        try {
            ScheduledFuture<?> deadline = deadlineScheduler.schedule(
                    () -> expireRequest(checked.requestId()),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            session.setDeadline(deadline);
        } catch (RuntimeException exception) {
            failSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }

        AiProvider provider;
        try {
            if (!isCurrentAndLocallyAuthorized(
                    session, readCurrentEpochMillisSafely())) {
                cancelSession(session);
                return ClientAiRequestDispatchStatus.CANCELLED;
            }
            provider = Objects.requireNonNull(
                    providerFactory.create(checked, credentialStore),
                    "client provider factory result");
        } catch (RuntimeException exception) {
            failSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }

        CompletionStage<AiResponse> completion;
        try {
            if (!isCurrentAndLocallyAuthorized(
                    session, readCurrentEpochMillisSafely())) {
                cancelSession(session);
                return ClientAiRequestDispatchStatus.CANCELLED;
            }
            completion = Objects.requireNonNull(
                    provider.complete(checked.toAiRequest(), session.cancellation.token()),
                    "AiProvider completion");
        } catch (RuntimeException exception) {
            failSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }

        CompletionAttachment attachment = new CompletionAttachment();
        try {
            completion.whenComplete((response, failure) ->
                    completeBufferedCompletion(
                            attachment, session, response, failure));
        } catch (RuntimeException exception) {
            attachment.discard();
            failSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        } catch (Error error) {
            attachment.discard();
            throw failSessionAfterPostAdmissionError(session, error);
        }
        CompletionSignal buffered = attachment.activateAfterAttachment();
        if (buffered != null) {
            completeSession(session, buffered.response(), buffered.failure());
        }
        return ClientAiRequestDispatchStatus.STARTED;
    }

    /**
     * Locally stages one exact physical-attempt offer without invoking its Provider.
     *
     * <p>Staging performs the same owner, binding, deadline, duplicate/tombstone, replacement,
     * scheduler and factory checks as ordinary admission. It records the resulting local Provider
     * and returns an ACK only after an {@link AiPhysicalAttemptClientGrantGate} is installed for
     * the exact immutable offer. The later grant handoff alone may cross the real Provider-start
     * boundary.
     */
    public ClientAiPhysicalAttemptPreparation preparePhysicalAttempt(
            AiPhysicalAttemptOffer offer, AiClientRequestDispatch dispatch) {
        rejectProposalHandoffReentrancy();
        AiPhysicalAttemptOffer checkedOffer = Objects.requireNonNull(offer, "offer");
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        if (!checkedOffer.identity().matches(checked)
                || checked.purpose() != AiRequestPurpose.REVIEW_ONLY_V1
                || !AiReviewOnlyContract.isCanonicalDispatch(checked)) {
            return ClientAiPhysicalAttemptPreparation.rejected(
                    ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED);
        }
        long now = readCurrentEpochMillisSafely();
        if (now < 0L) {
            return ClientAiPhysicalAttemptPreparation.rejected(
                    ClientAiRequestDispatchStatus.EXPIRED);
        }
        expireThrough(now);
        List<ActiveSession> cancelled = new ArrayList<>();
        ActiveSession session;
        synchronized (lock) {
            ClientAiRequestDispatchStatus localStatus = localStatusLocked(checked, now);
            if (localStatus != ClientAiRequestDispatchStatus.STARTED) {
                return ClientAiPhysicalAttemptPreparation.rejected(localStatus);
            }
            if (sessionsByRequest.containsKey(checked.requestId())
                    || tombstoneExpiryByRequest.containsKey(checked.requestId())) {
                return ClientAiPhysicalAttemptPreparation.rejected(
                        ClientAiRequestDispatchStatus.DUPLICATE_REQUEST);
            }
            if (tombstoneExpiryByRequest.size() + sessionsByRequest.size()
                    >= MAX_REQUEST_TOMBSTONES) {
                return ClientAiPhysicalAttemptPreparation.rejected(
                        ClientAiRequestDispatchStatus.TOMBSTONE_CAPACITY);
            }
            ActiveSession previous = sessionsByBot.get(checked.botId());
            if (previous != null) {
                detachLocked(previous);
                cancelled.add(previous);
            }
            BotBindingEpoch bindingEpoch = bindingEpochForLocked(checked.botId());
            bindingEpoch.activeSessions++;
            session = new ActiveSession(checked, bindingEpoch, bindingEpoch.value);
            sessionsByRequest.put(checked.requestId(), session);
            sessionsByBot.put(checked.botId(), session);
        }
        try {
            cancelAll(cancelled);
        } catch (Throwable cancellationFailure) {
            failReplacementAfterCancellationFailure(session, cancellationFailure);
        }

        long dispatchTtlMillis = checked.expiresAtEpochMillis()
                - checked.issuedAtEpochMillis();
        long delayMillis = Math.min(
                checked.expiresAtEpochMillis() - now,
                dispatchTtlMillis);
        try {
            ScheduledFuture<?> deadline = deadlineScheduler.schedule(
                    () -> expireRequest(checked.requestId()),
                    delayMillis,
                    TimeUnit.MILLISECONDS);
            session.setDeadline(deadline);
        } catch (RuntimeException exception) {
            failSession(session);
            return ClientAiPhysicalAttemptPreparation.rejected(
                    ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE);
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }

        AiProvider provider;
        try {
            if (!isCurrentAndLocallyAuthorized(
                    session, readCurrentEpochMillisSafely())) {
                cancelSession(session);
                return ClientAiPhysicalAttemptPreparation.rejected(
                        ClientAiRequestDispatchStatus.CANCELLED);
            }
            provider = Objects.requireNonNull(
                    providerFactory.create(checked, credentialStore),
                    "client provider factory result");
        } catch (RuntimeException exception) {
            failSession(session);
            return ClientAiPhysicalAttemptPreparation.rejected(
                    ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE);
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }

        try {
            if (!isCurrentAndLocallyAuthorized(
                    session, readCurrentEpochMillisSafely())) {
                cancelSession(session);
                return ClientAiPhysicalAttemptPreparation.rejected(
                        ClientAiRequestDispatchStatus.CANCELLED);
            }
            AiPhysicalAttemptClientGrantGate gate = new AiPhysicalAttemptClientGrantGate(
                    checkedOffer,
                    checked,
                    session.bindingEpochValue,
                    () -> currentBindingEpochValue(session),
                    currentEpochMillis,
                    () -> isCurrentAndLocallyStaged(
                            session, readCurrentEpochMillisSafely()),
                    (grant, lease) -> startGrantedPhysicalAttempt(
                            session, grant, lease));
            if (!session.installPhysicalAttempt(provider, gate)) {
                cancelSession(session);
                return ClientAiPhysicalAttemptPreparation.rejected(
                        ClientAiRequestDispatchStatus.CANCELLED);
            }
        } catch (RuntimeException exception) {
            failSession(session);
            return ClientAiPhysicalAttemptPreparation.rejected(
                    ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE);
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }
        return ClientAiPhysicalAttemptPreparation.prepared(checkedOffer.prepareAck());
    }

    /**
     * Delivers one server grant only to the exact local session that staged its matching offer.
     *
     * <p>A mismatched identity cannot retire another live request. A local deadline, rebind,
     * disconnect or cancellation result is fail-closed and retires this session before a later
     * replay can reach its Provider.
     */
    public AiPhysicalAttemptClientGrantStatus acceptPhysicalAttemptStartGrant(
            AiPhysicalAttemptStartGrant grant) {
        rejectProposalHandoffReentrancy();
        AiPhysicalAttemptStartGrant checked = Objects.requireNonNull(grant, "grant");
        ActiveSession session;
        synchronized (lock) {
            session = sessionsByRequest.get(
                    checked.identity().dispatchReceipt().requestId());
        }
        if (session == null) {
            return AiPhysicalAttemptClientGrantStatus.LOCAL_SESSION_INACTIVE;
        }
        AiPhysicalAttemptClientGrantGate gate = session.physicalAttemptGate();
        if (gate == null || !gate.expectedIdentity().equals(checked.identity())) {
            return AiPhysicalAttemptClientGrantStatus.IDENTITY_MISMATCH;
        }
        final AiPhysicalAttemptClientGrantStatus status;
        try {
            status = gate.accept(checked);
        } catch (RuntimeException exception) {
            failSession(session);
            return AiPhysicalAttemptClientGrantStatus.CLOSED;
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }
        if (status != AiPhysicalAttemptClientGrantStatus.HANDED_OFF
                && status != AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF
                && status != AiPhysicalAttemptClientGrantStatus.IDENTITY_MISMATCH) {
            cancelSession(session);
        }
        return status;
    }

    /**
     * Returns whether an exact offer remains locally staged and ACK-safe at this instant.
     *
     * <p>This exposes no Provider, credential, request text, or mutable lease. The physical
     * client uses it immediately before sending a prepare ACK so a queued ingress cannot settle a
     * server reservation after local cancellation, rebind, owner change, connection loss, or
     * deadline expiry won the race.
     */
    public boolean isCurrentPreparedPhysicalAttempt(AiPhysicalAttemptIdentity identity) {
        rejectProposalHandoffReentrancy();
        AiPhysicalAttemptIdentity checked = Objects.requireNonNull(identity, "identity");
        long now = readCurrentEpochMillisSafely();
        if (now < 0L) {
            return false;
        }
        expireThrough(now);
        synchronized (lock) {
            ActiveSession session = sessionsByRequest.get(
                    checked.dispatchReceipt().requestId());
            return session != null
                    && session.matchesPhysicalAttemptIdentity(checked)
                    && isCurrentLocked(session)
                    && !session.cancellation.isCancellationRequested()
                    && isBindingEpochCurrentLocked(session)
                    && localStatusLocked(session.dispatch, now)
                            == ClientAiRequestDispatchStatus.STARTED;
        }
    }

    /** Runs under the grant-gate handoff and owns the one actual Provider-start boundary. */
    private void startGrantedPhysicalAttempt(
            ActiveSession session,
            AiPhysicalAttemptStartGrant grant,
            AiPhysicalAttemptClientGrantGate.LocalStartLease lease) {
        if (!session.matchesPhysicalAttemptIdentity(grant.identity())
                || !isCurrentAndLocallyAuthorized(
                        session, readCurrentEpochMillisSafely())) {
            lease.release();
            cancelSession(session);
            return;
        }
        AiProvider provider = session.preparedProvider();
        if (provider == null) {
            lease.release();
            failSession(session);
            return;
        }

        CompletionStage<AiResponse> completion;
        try {
            if (!lease.tryClaimPhysicalStart()) {
                cancelSession(session);
                return;
            }
            completion = provider.complete(
                    session.dispatch.toAiRequest(), session.cancellation.token());
            if (completion == null) {
                throw new NullPointerException("AiProvider completion");
            }
        } catch (RuntimeException exception) {
            failSession(session);
            return;
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }

        CompletionAttachment attachment = new CompletionAttachment();
        try {
            completion.whenComplete((response, failure) ->
                    completeBufferedCompletion(
                            attachment, session, response, failure));
        } catch (RuntimeException exception) {
            attachment.discard();
            failSession(session);
            return;
        } catch (Error error) {
            attachment.discard();
            throw failSessionAfterPostAdmissionError(session, error);
        }
        CompletionSignal buffered = attachment.activateAfterAttachment();
        if (buffered != null) {
            completeSession(session, buffered.response(), buffered.failure());
        }
    }

    private void completeBufferedCompletion(
            CompletionAttachment attachment,
            ActiveSession session,
            AiResponse response,
            Throwable failure) {
        CompletionSignal signal = attachment.recordCallback(response, failure);
        if (signal != null) {
            completeSession(session, signal.response(), signal.failure());
        }
    }

    /** Cancels a still-active local request by its server-generated request id. */
    public boolean cancelRequest(UUID requestId) {
        rejectProposalHandoffReentrancy();
        Objects.requireNonNull(requestId, "requestId");
        ActiveSession session;
        synchronized (lock) {
            session = sessionsByRequest.get(requestId);
            if (session == null) {
                return false;
            }
            detachLocked(session);
        }
        cancelAndObserve(session, AiClientSponsoredTerminalStatus.CANCELLED);
        return true;
    }

    /**
     * Applies a server-originated cancellation only when it matches the exact active dispatch and
     * the current physical-client epoch is still live. A stale packet is a no-op rather than a
     * broad bot/request-id cancellation.
     */
    public boolean cancel(AiRequestCancellationPayload cancellation) {
        rejectProposalHandoffReentrancy();
        AiRequestCancellationPayload checked = Objects.requireNonNull(
                cancellation, "cancellation");
        ActiveSession session;
        synchronized (lock) {
            if (!isSessionActive()
                    || localOwnerId == null
                    || !localOwnerId.equals(checked.ownerId())) {
                return false;
            }
            session = sessionsByRequest.get(checked.requestId());
            if (session == null || !checked.matches(session.dispatch)) {
                return false;
            }
            detachLocked(session);
        }
        cancelAndObserve(session, AiClientSponsoredTerminalStatus.CANCELLED);
        return true;
    }

    /** Cancels the one active local request for a bot, if any. */
    public boolean cancelBot(UUID botId) {
        rejectProposalHandoffReentrancy();
        Objects.requireNonNull(botId, "botId");
        ActiveSession session;
        synchronized (lock) {
            session = sessionsByBot.get(botId);
            if (session == null) {
                return false;
            }
            detachLocked(session);
        }
        cancelAndObserve(session, AiClientSponsoredTerminalStatus.CANCELLED);
        return true;
    }

    /**
     * Invalidates every active or already-queued proposal for one bot's local credential binding.
     *
     * <p>Binding editors must call this before {@link #cancelBot(UUID)} and before replacing or
     * removing the binding. The epoch deliberately belongs to one bot, not to the physical
     * connection, so an ordinary local rebind cannot invalidate unrelated bots.
     */
    public void advanceBindingEpoch(UUID botId) {
        rejectProposalHandoffReentrancy();
        Objects.requireNonNull(botId, "botId");
        synchronized (lock) {
            BotBindingEpoch bindingEpoch = bindingEpochsByBot.get(botId);
            if (bindingEpoch != null) {
                bindingEpoch.advance();
            }
        }
    }

    /** Cancels every local Provider request without sending a C2S payload. */
    public int cancelAll() {
        rejectProposalHandoffReentrancy();
        List<ActiveSession> cancelled;
        synchronized (lock) {
            cancelled = detachAllLocked();
        }
        cancelAll(cancelled);
        return cancelled.size();
    }

    /** Expires requests against a testable wall-clock source; returns the number retired. */
    public int expireThrough(long nowEpochMillis) {
        rejectProposalHandoffReentrancy();
        if (nowEpochMillis < 0L) {
            throw new IllegalArgumentException("nowEpochMillis must not be negative");
        }
        List<ActiveSession> expired = new ArrayList<>();
        synchronized (lock) {
            collectExpiredLocked(nowEpochMillis, expired);
        }
        cancelAll(expired);
        return expired.size();
    }

    public int activeRequestCount() {
        rejectProposalHandoffReentrancy();
        synchronized (lock) {
            return sessionsByRequest.size();
        }
    }

    private void expireRequest(UUID requestId) {
        ActiveSession session;
        synchronized (lock) {
            session = sessionsByRequest.get(requestId);
            if (session == null) {
                return;
            }
            detachLocked(session);
        }
        cancelAndObserve(session, AiClientSponsoredTerminalStatus.CANCELLED);
    }

    private void completeSession(
            ActiveSession session, AiResponse response, Throwable failure) {
        if (deferProposalHandoffReentrantCompletion(session)) {
            return;
        }
        try {
            completeSessionSafely(session, response, failure);
        } catch (Error error) {
            throw failSessionAfterPostAdmissionError(session, error);
        }
    }

    /**
     * Fences a same-thread completion reached from the external proposal handoff.
     *
     * <p>The outer handoff owns {@link #lock}; another thread cannot enter this method until it
     * has released the monitor and cleared the scope. A non-null scope therefore represents only
     * monitor reentrancy from that handoff. This branch only detaches exact indexes and records
     * the required failure cleanup. Token cancellation and terminal observation stay outside the
     * outer handoff's monitor scope.
     */
    private boolean deferProposalHandoffReentrantCompletion(ActiveSession session) {
        synchronized (lock) {
            ProposalHandoffScope scope = activeProposalHandoff;
            if (scope == null) {
                return false;
            }
            AiClientSponsoredTerminalStatus terminalStatus =
                    detachIfCurrentLocked(session)
                            ? AiClientSponsoredTerminalStatus.FAILED
                            : null;
            scope.defer(session, terminalStatus);
            return true;
        }
    }

    /**
     * Handles one provider completion after a session has been admitted.
     *
     * <p>Any {@link Error} from a post-admission dependency is caught by the
     * outer method so the exact session is detached and observed before the
     * completion action reports that failure to its {@link CompletionStage}.
     */
    private void completeSessionSafely(
            ActiveSession session, AiResponse response, Throwable failure) {
        if (failure != null
                || response == null
                || !isAcceptableResponse(session.dispatch, response)) {
            failSession(session);
            return;
        }

        final AiProposalPayload proposal;
        try {
            proposal = toProposal(session.dispatch, response);
            /*
             * Keep a defensive check at the handoff boundary. The payload constructor already
             * enforces this exact codec bound, but no provider response should ever reach the
             * physical send path merely because a future payload implementation regresses it.
             */
            if (proposal.encodedByteLength() > AiProposalPayload.MAX_ENCODED_FRAME_BYTES) {
                failSession(session);
                return;
            }
        } catch (RuntimeException exception) {
            failSession(session);
            return;
        }

        /*
         * Keep the session current until the non-blocking physical-client handoff has been made.
         * This makes a concurrent cancellation/owner change win before the C2S proposal is
         * queued.
         */
        AiClientSponsoredTerminalStatus terminalStatus = null;
        List<DeferredSessionFinalization> deferredFinalizations = List.of();
        boolean primaryErrorOwnsCompletionCleanup = false;
        try {
            synchronized (lock) {
                if (!isCurrentLocked(session)
                        || session.cancellation.isCancellationRequested()
                        || !isBindingEpochCurrentLocked(session)
                        || localStatusLocked(
                                session.dispatch,
                                readCurrentEpochMillisSafely())
                        != ClientAiRequestDispatchStatus.STARTED) {
                    if (detachIfCurrentLocked(session)) {
                        terminalStatus = AiClientSponsoredTerminalStatus.CANCELLED;
                    }
                } else {
                    BindingEpochHandoff bindingEpoch = createBindingEpochHandoffLocked(session);
                    ProposalHandoffScope handoffScope = new ProposalHandoffScope();
                    boolean handedOff = false;
                    activeProposalHandoff = handoffScope;
                    try {
                        proposalHandoff.accept(session.dispatch, proposal, bindingEpoch);
                        handedOff = !handoffScope.indirectCompletionSeen();
                    } catch (RuntimeException ignored) {
                        // A send handoff failure must not trigger a retry or reveal model output.
                    } finally {
                        activeProposalHandoff = null;
                        deferredFinalizations = handoffScope.close();
                        if (!handedOff) {
                            /* A failed or reentrant handoff never transfers its epoch lease. */
                            bindingEpoch.release();
                        }
                        if (detachIfCurrentLocked(session)) {
                            terminalStatus = handedOff
                                    ? AiClientSponsoredTerminalStatus.SUCCEEDED
                                    : AiClientSponsoredTerminalStatus.FAILED;
                        }
                    }
                }
            }
        } catch (Error primary) {
            primaryErrorOwnsCompletionCleanup = true;
            Throwable cleanupFailure = null;
            try {
                if (terminalStatus == null) {
                    /*
                     * No terminal outcome has been chosen yet. Detach before cancelling the
                     * provider token so a token listener cannot claim a competing CANCELLED
                     * terminal while this trusted completion failure is being closed.
                     */
                    failSession(session);
                } else {
                    finishCompletion(session, terminalStatus);
                }
            } catch (Throwable currentFailure) {
                cleanupFailure = currentFailure;
            }
            cleanupFailure = appendCompletionFailure(
                    cleanupFailure,
                    finishDeferredCompletions(deferredFinalizations));
            addSuppressedIfDistinct(primary, cleanupFailure);
            throw primary;
        } finally {
            if (!primaryErrorOwnsCompletionCleanup) {
                finishCompletionBatch(
                        session, terminalStatus, deferredFinalizations);
            }
        }
    }

    /** Finishes every deferred session even when another terminal cleanup reports a failure. */
    private void finishCompletionBatch(
            ActiveSession session,
            AiClientSponsoredTerminalStatus terminalStatus,
            List<DeferredSessionFinalization> deferredFinalizations) {
        Throwable cleanupFailure = null;
        try {
            finishCompletion(session, terminalStatus);
        } catch (Throwable currentFailure) {
            cleanupFailure = currentFailure;
        }
        cleanupFailure = appendCompletionFailure(
                cleanupFailure,
                finishDeferredCompletions(deferredFinalizations));
        if (cleanupFailure != null) {
            rethrowTerminalFailure(cleanupFailure);
        }
    }

    private Throwable finishDeferredCompletions(
            List<DeferredSessionFinalization> deferredFinalizations) {
        Throwable cleanupFailure = null;
        for (DeferredSessionFinalization deferred : deferredFinalizations) {
            try {
                finishCompletion(deferred.session(), deferred.terminalStatus());
            } catch (Throwable currentFailure) {
                cleanupFailure = appendCompletionFailure(
                        cleanupFailure, currentFailure);
            }
        }
        return cleanupFailure;
    }

    private static Throwable appendCompletionFailure(
            Throwable primary, Throwable supplemental) {
        if (primary == null) {
            return supplemental;
        }
        addSuppressedIfDistinct(primary, supplemental);
        return primary;
    }

    private void finishCompletion(
            ActiveSession session, AiClientSponsoredTerminalStatus terminalStatus) {
        if (terminalStatus != null) {
            /*
             * The provider has completed, but signalling the token still releases any
             * provider listener which races its terminal callback. This is idempotent for
             * the normal success path and ensures terminal sessions cannot retain a
             * cancellation registration or a transport-owned credential copy.
             */
            cancelAndObserve(session, terminalStatus);
        } else {
            /* A concurrent replacement owns the observation but not this token cleanup. */
            session.cancel();
        }
    }

    private boolean isCurrentAndLocallyAuthorized(ActiveSession session, long now) {
        synchronized (lock) {
            return isCurrentLocked(session)
                    && !session.cancellation.isCancellationRequested()
                    && isBindingEpochCurrentLocked(session)
                    && localStatusLocked(session.dispatch, now)
                    == ClientAiRequestDispatchStatus.STARTED;
        }
    }

    /**
     * Preserves the grant gate's distinct binding-epoch failure result.
     *
     * <p>Credential, owner, deadline, connection, and current-session checks remain active here.
     * The epoch itself is intentionally left to the gate's separate exact epoch comparison so a
     * rebind cannot be misreported as a generic inactive local session.
     */
    private boolean isCurrentAndLocallyStaged(ActiveSession session, long now) {
        synchronized (lock) {
            return isCurrentLocked(session)
                    && !session.cancellation.isCancellationRequested()
                    && localStatusLocked(session.dispatch, now)
                    == ClientAiRequestDispatchStatus.STARTED;
        }
    }

    private void cancelSession(ActiveSession session) {
        retireSession(session, AiClientSponsoredTerminalStatus.CANCELLED);
    }

    private void failSession(ActiveSession session) {
        retireSession(session, AiClientSponsoredTerminalStatus.FAILED);
    }

    /**
     * Closes a session before rethrowing an {@link Error} from a dependency
     * reached after that session was placed in the active indexes.
     */
    private Error failSessionAfterPostAdmissionError(
            ActiveSession session, Error primary) {
        Throwable cleanupFailure = null;
        try {
            failSession(session);
        } catch (Throwable currentFailure) {
            cleanupFailure = currentFailure;
        }
        addSuppressedIfDistinct(primary, cleanupFailure);
        return primary;
    }

    private static void addSuppressedIfDistinct(
            Throwable primary, Throwable supplemental) {
        if (supplemental != null && supplemental != primary) {
            primary.addSuppressed(supplemental);
        }
    }

    private void retireSession(
            ActiveSession session, AiClientSponsoredTerminalStatus terminalStatus) {
        boolean detached;
        synchronized (lock) {
            detached = detachIfCurrentLocked(session);
        }
        if (detached) {
            cancelAndObserve(session, terminalStatus);
        } else {
            // The first detacher owns the terminal observation, but this callback still cleans up.
            session.cancel();
        }
    }

    private boolean detachIfCurrentLocked(ActiveSession session) {
        if (!isCurrentLocked(session)) {
            return false;
        }
        detachLocked(session);
        return true;
    }

    private boolean isCurrentLocked(ActiveSession session) {
        return sessionsByRequest.get(session.dispatch.requestId()) == session
                && sessionsByBot.get(session.dispatch.botId()) == session;
    }

    private void detachLocked(ActiveSession session) {
        sessionsByRequest.remove(session.dispatch.requestId(), session);
        sessionsByBot.remove(session.dispatch.botId(), session);
        session.cancelDeadline();
        releaseActiveSessionBindingEpochLocked(session);
        recordTombstoneLocked(session.dispatch);
    }

    private List<ActiveSession> detachAllLocked() {
        List<ActiveSession> sessions = new ArrayList<>(sessionsByRequest.values());
        sessionsByRequest.clear();
        sessionsByBot.clear();
        sessions.forEach(session -> {
            session.cancelDeadline();
            releaseActiveSessionBindingEpochLocked(session);
            recordTombstoneLocked(session.dispatch);
        });
        return sessions;
    }

    private void collectExpiredLocked(long now, List<ActiveSession> expired) {
        List<ActiveSession> stale = sessionsByRequest.values().stream()
                .filter(session -> now >= session.dispatch.expiresAtEpochMillis())
                .toList();
        for (ActiveSession session : stale) {
            detachLocked(session);
            expired.add(session);
        }
        tombstoneExpiryByRequest.entrySet().removeIf(
                entry -> now >= entry.getValue());
    }

    private void recordTombstoneLocked(AiClientRequestDispatch dispatch) {
        UUID requestId = dispatch.requestId();
        if (!tombstoneExpiryByRequest.containsKey(requestId)
                && tombstoneExpiryByRequest.size() >= MAX_REQUEST_TOMBSTONES) {
            /*
             * Active sessions reserve their eventual tombstone slot before they start. Keeping
             * this fail-closed guard makes a future lifecycle edit unable to evict a still-live
             * replay defense silently.
             */
            return;
        }
        tombstoneExpiryByRequest.put(requestId, dispatch.expiresAtEpochMillis());
    }

    private ClientAiRequestDispatchStatus localStatusLocked(
            AiClientRequestDispatch dispatch, long now) {
        if (!isSessionActive()) {
            return ClientAiRequestDispatchStatus.CANCELLED;
        }
        if (localOwnerId == null || !localOwnerId.equals(dispatch.ownerId())) {
            return ClientAiRequestDispatchStatus.NOT_LOCAL_OWNER;
        }
        if (now < 0L
                || now >= dispatch.expiresAtEpochMillis()
                || dispatch.issuedAtEpochMillis() > MAX_CLOCK_SKEW_MILLIS
                        && now < dispatch.issuedAtEpochMillis() - MAX_CLOCK_SKEW_MILLIS) {
            return ClientAiRequestDispatchStatus.EXPIRED;
        }
        Optional<BotCredentialBinding> binding;
        try {
            binding = credentialStore.findBinding(
                    dispatch.serverInstanceId(), dispatch.ownerId(), dispatch.botId());
        } catch (RuntimeException exception) {
            return ClientAiRequestDispatchStatus.BINDING_MISSING;
        }
        if (binding.isEmpty()) {
            return ClientAiRequestDispatchStatus.BINDING_MISSING;
        }
        if (!binding.orElseThrow().agentId().equals(dispatch.agentId())) {
            return ClientAiRequestDispatchStatus.AGENT_MISMATCH;
        }
        return ClientAiRequestDispatchStatus.STARTED;
    }

    /**
     * Rejects callbacks that try to mutate the controller while its final handoff check owns the
     * monitor. Allowing Java monitor reentrancy here would let an observer run while the outer
     * handoff still holds the lock and would weaken the cancellation-before-queue ordering.
     */
    private void rejectProposalHandoffReentrancy() {
        if (Thread.holdsLock(lock)) {
            throw new IllegalStateException(
                    "ClientAiRequestSessionController must not be re-entered from a callback");
        }
    }

    private boolean isSessionActive() {
        try {
            return sessionActive.getAsBoolean();
        } catch (RuntimeException exception) {
            return false;
        }
    }

    private long readCurrentEpochMillisSafely() {
        try {
            long now = currentEpochMillis.getAsLong();
            return now < 0L ? -1L : now;
        } catch (RuntimeException exception) {
            return -1L;
        }
    }

    private static boolean isAcceptableResponse(
            AiClientRequestDispatch dispatch, AiResponse response) {
        if (dispatch.purpose() == AiRequestPurpose.REVIEW_ONLY_V1) {
            return AiReviewOnlyContract.isCanonicalToolResponse(dispatch, response);
        }
        if (!dispatch.requestId().equals(response.requestId())
                || !dispatch.providerId().equals(response.providerId())
                || !dispatch.model().equals(response.model())
                || response.finishReason() != AiFinishReason.TOOL_CALLS
                || response.toolCalls().isEmpty()
                || response.toolCalls().size() > AiProposalPayload.MAX_TOOL_CALLS
                || !dispatch.options().toolCallsAllowed()) {
            return false;
        }
        return true;
    }

    private static AiProposalPayload toProposal(
            AiClientRequestDispatch dispatch, AiResponse response) {
        List<AiProposalToolCallPayload> toolCalls = new ArrayList<>(
                response.toolCalls().size());
        for (AiRawToolCall toolCall : response.toolCalls()) {
            toolCalls.add(new AiProposalToolCallPayload(
                    toolCall.callId(), toolCall.name(), toolCall.argumentsJson()));
        }
        return new AiProposalPayload(
                dispatch.botId(),
                dispatch.agentId(),
                dispatch.generation(),
                dispatch.requestId(),
                dispatch.nonce(),
                dispatch.revision(),
                dispatch.purpose() == AiRequestPurpose.REVIEW_ONLY_V1
                        ? ""
                        : response.outputText(),
                toolCalls);
    }

    private void cancelAll(List<ActiveSession> sessions) {
        Throwable failure = null;
        for (ActiveSession session : sessions) {
            try {
                cancelAndObserve(session, AiClientSponsoredTerminalStatus.CANCELLED);
            } catch (Throwable currentFailure) {
                if (failure == null) {
                    failure = currentFailure;
                } else if (currentFailure != failure) {
                    failure.addSuppressed(currentFailure);
                }
            }
        }
        if (failure != null) {
            rethrowTerminalFailure(failure);
        }
    }

    /** Runs after the caller released {@link #lock}; observer failures cannot retain a session. */
    private void observeTerminal(
            ActiveSession session, AiClientSponsoredTerminalStatus terminalStatus) {
        if (!session.claimTerminalObservation()) {
            return;
        }
        AiClientSponsoredTerminalObservation observation =
                new AiClientSponsoredTerminalObservation(
                        AiRequestDispatchReceipt.fromDispatch(session.dispatch),
                        Objects.requireNonNull(terminalStatus, "terminalStatus"));
        deliverTerminalObservation(observation);
    }

    /**
     * Runs for the exact detacher after {@link #lock} is released.
     *
     * <p>It alone consumes retained cancellation cleanup failures, after token cancellation and
     * terminal observation. A stale completion may still request token cancellation, but cannot
     * steal the terminal owner's failure report.
     */
    private void cancelAndObserve(
            ActiveSession session, AiClientSponsoredTerminalStatus terminalStatus) {
        Throwable cancellationInvocationFailure = null;
        try {
            session.cancel();
        } catch (Throwable currentFailure) {
            cancellationInvocationFailure = currentFailure;
        }
        Throwable observationFailure = null;
        try {
            observeTerminal(session, terminalStatus);
        } catch (Throwable currentFailure) {
            observationFailure = currentFailure;
        }
        Throwable cancellationFailure = session.takeCancellationCleanupFailure();
        if (cancellationFailure == null) {
            cancellationFailure = cancellationInvocationFailure;
        } else {
            addSuppressedIfDistinct(
                    cancellationFailure, cancellationInvocationFailure);
        }
        if (cancellationFailure != null) {
            addSuppressedIfDistinct(cancellationFailure, observationFailure);
            rethrowTerminalFailure(cancellationFailure);
        }
        if (observationFailure != null) {
            rethrowTerminalFailure(observationFailure);
        }
    }

    private void deliverTerminalObservation(
            AiClientSponsoredTerminalObservation observation) {
        try {
            terminalObserver.observe(observation);
        } catch (RuntimeException ignored) {
            // An observational handoff must never resurrect or retain local client work.
        }
    }

    private static void rethrowTerminalFailure(Throwable failure) {
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        throw new IllegalStateException("unexpected checked terminal callback failure", failure);
    }

    /** Cleans a newly installed replacement if retiring its predecessor reported a fatal error. */
    private void failReplacementAfterCancellationFailure(
            ActiveSession replacement, Throwable cancellationFailure) {
        try {
            failSession(replacement);
        } catch (Throwable replacementFailure) {
            if (replacementFailure != cancellationFailure) {
                cancellationFailure.addSuppressed(replacementFailure);
            }
        }
        rethrowTerminalFailure(cancellationFailure);
    }

    private static ProposalHandoff synchronousProposalHandoff(
            BiConsumer<AiClientRequestDispatch, AiProposalPayload> proposalSink) {
        BiConsumer<AiClientRequestDispatch, AiProposalPayload> checked = Objects.requireNonNull(
                proposalSink, "proposalSink");
        return (dispatch, proposal, bindingEpoch) -> {
            try {
                checked.accept(dispatch, proposal);
            } finally {
                bindingEpoch.release();
            }
        };
    }

    private BotBindingEpoch bindingEpochForLocked(UUID botId) {
        return bindingEpochsByBot.computeIfAbsent(botId, ignored -> new BotBindingEpoch());
    }

    private boolean isBindingEpochCurrentLocked(ActiveSession session) {
        return bindingEpochsByBot.get(session.dispatch.botId()) == session.bindingEpoch
                && session.bindingEpoch.value == session.bindingEpochValue;
    }

    private long currentBindingEpochValue(ActiveSession session) {
        synchronized (lock) {
            BotBindingEpoch current = bindingEpochsByBot.get(session.dispatch.botId());
            return current == session.bindingEpoch ? current.value : Long.MIN_VALUE;
        }
    }

    private BindingEpochHandoff createBindingEpochHandoffLocked(ActiveSession session) {
        session.bindingEpoch.queuedHandoffs++;
        return new ActiveBindingEpochHandoff(
                session.dispatch.botId(), session.bindingEpoch, session.bindingEpochValue);
    }

    private void releaseActiveSessionBindingEpochLocked(ActiveSession session) {
        BotBindingEpoch bindingEpoch = session.bindingEpoch;
        if (bindingEpoch.activeSessions > 0) {
            bindingEpoch.activeSessions--;
        }
        releaseUnusedBindingEpochLocked(session.dispatch.botId(), bindingEpoch);
    }

    private void releaseUnusedBindingEpochLocked(UUID botId, BotBindingEpoch bindingEpoch) {
        if (bindingEpoch.activeSessions == 0 && bindingEpoch.queuedHandoffs == 0) {
            bindingEpochsByBot.remove(botId, bindingEpoch);
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
        return value;
    }

    private final class ActiveBindingEpochHandoff implements BindingEpochHandoff {
        private final UUID botId;
        private final BotBindingEpoch bindingEpoch;
        private final long bindingEpochValue;
        private boolean released;

        private ActiveBindingEpochHandoff(
                UUID botId, BotBindingEpoch bindingEpoch, long bindingEpochValue) {
            this.botId = botId;
            this.bindingEpoch = bindingEpoch;
            this.bindingEpochValue = bindingEpochValue;
        }

        @Override
        public boolean isCurrentFor(UUID expectedBotId) {
            if (expectedBotId == null || !botId.equals(expectedBotId)) {
                return false;
            }
            synchronized (lock) {
                return !released
                        && bindingEpochsByBot.get(botId) == bindingEpoch
                        && bindingEpoch.value == bindingEpochValue;
            }
        }

        @Override
        public void release() {
            synchronized (lock) {
                if (released) {
                    return;
                }
                released = true;
                if (bindingEpoch.queuedHandoffs > 0) {
                    bindingEpoch.queuedHandoffs--;
                }
                releaseUnusedBindingEpochLocked(botId, bindingEpoch);
            }
        }
    }

    private static final class BotBindingEpoch {
        private long value = 1L;
        private int activeSessions;
        private int queuedHandoffs;

        private void advance() {
            value = value == Long.MAX_VALUE ? 1L : value + 1L;
        }
    }

    /**
     * Defers an externally invoked completion callback until its {@code whenComplete} attachment
     * returned normally. A stage is therefore unable to publish a provisional proposal before an
     * attachment failure is reported to the direct non-R1 admission path.
     */
    private static final class CompletionAttachment {
        private CompletionAttachmentState state = CompletionAttachmentState.ATTACHING;
        private CompletionSignal signal;
        private boolean callbackObserved;

        private synchronized CompletionSignal recordCallback(
                AiResponse response, Throwable failure) {
            if (state == CompletionAttachmentState.DISCARDED || callbackObserved) {
                return null;
            }
            callbackObserved = true;
            signal = new CompletionSignal(response, failure);
            if (state != CompletionAttachmentState.ACTIVE) {
                return null;
            }
            CompletionSignal committed = signal;
            signal = null;
            return committed;
        }

        private synchronized CompletionSignal activateAfterAttachment() {
            if (state != CompletionAttachmentState.ATTACHING) {
                throw new IllegalStateException("completion attachment lost its provisional state");
            }
            state = CompletionAttachmentState.ACTIVE;
            CompletionSignal committed = signal;
            signal = null;
            return committed;
        }

        private synchronized void discard() {
            state = CompletionAttachmentState.DISCARDED;
            signal = null;
        }
    }

    private enum CompletionAttachmentState {
        ATTACHING,
        ACTIVE,
        DISCARDED
    }

    private record CompletionSignal(AiResponse response, Throwable failure) {}

    /** Lock-confined evidence that an external handoff synchronously completed another session. */
    private static final class ProposalHandoffScope {
        private final List<DeferredSessionFinalization> deferredFinalizations = new ArrayList<>();
        private boolean indirectCompletionSeen;
        private boolean closed;

        private void defer(
                ActiveSession session,
                AiClientSponsoredTerminalStatus terminalStatus) {
            if (closed) {
                throw new IllegalStateException("proposal handoff scope is already closed");
            }
            indirectCompletionSeen = true;
            for (DeferredSessionFinalization deferred : deferredFinalizations) {
                if (deferred.session() == session) {
                    return;
                }
            }
            deferredFinalizations.add(new DeferredSessionFinalization(
                    session, terminalStatus));
        }

        private boolean indirectCompletionSeen() {
            return indirectCompletionSeen;
        }

        private List<DeferredSessionFinalization> close() {
            if (closed) {
                throw new IllegalStateException("proposal handoff scope is already closed");
            }
            closed = true;
            return List.copyOf(deferredFinalizations);
        }
    }

    /** A structurally detached nested completion whose external cleanup must run after unlock. */
    private static final class DeferredSessionFinalization {
        private final ActiveSession session;
        private final AiClientSponsoredTerminalStatus terminalStatus;

        private DeferredSessionFinalization(
                ActiveSession session,
                AiClientSponsoredTerminalStatus terminalStatus) {
            this.session = Objects.requireNonNull(session, "session");
            this.terminalStatus = terminalStatus;
        }

        private ActiveSession session() {
            return session;
        }

        private AiClientSponsoredTerminalStatus terminalStatus() {
            return terminalStatus;
        }
    }

    private static final class ActiveSession {
        private final AiClientRequestDispatch dispatch;
        private final BotBindingEpoch bindingEpoch;
        private final long bindingEpochValue;
        private final CancellationTokenSource cancellation = new CancellationTokenSource();
        private ScheduledFuture<?> deadline;
        private AiProvider preparedProvider;
        private AiPhysicalAttemptClientGrantGate physicalAttemptGate;
        private Throwable cancellationCleanupFailure;
        /* Guarded by this session monitor; closes late deadline installation before token notify. */
        private boolean cancellationStarted;
        private boolean terminalObservationClaimed;

        private ActiveSession(
                AiClientRequestDispatch dispatch,
                BotBindingEpoch bindingEpoch,
                long bindingEpochValue) {
            this.dispatch = dispatch;
            this.bindingEpoch = bindingEpoch;
            this.bindingEpochValue = bindingEpochValue;
        }

        private void setDeadline(ScheduledFuture<?> deadline) {
            ScheduledFuture<?> checked = Objects.requireNonNull(deadline, "deadline");
            boolean cancelImmediately;
            synchronized (this) {
                cancelImmediately = cancellationStarted;
                if (!cancelImmediately) {
                    this.deadline = checked;
                }
            }
            if (cancelImmediately) {
                checked.cancel(false);
            }
        }

        private synchronized void cancelDeadline() {
            if (deadline != null) {
                try {
                    deadline.cancel(false);
                } catch (RuntimeException ignored) {
                    // A third-party scheduler cannot prevent cancellation-token cleanup.
                } catch (Error failure) {
                    /*
                     * Structural detachment must continue even when a third-party future
                     * rejects cancellation. The terminal owner reports this retained failure
                     * after token cancellation and terminal observation.
                     */
                    recordCancellationCleanupFailure(failure);
                } finally {
                    deadline = null;
                }
            }
        }

        private void cancel() {
            AiPhysicalAttemptClientGrantGate gate;
            synchronized (this) {
                cancellationStarted = true;
                gate = physicalAttemptGate;
            }
            if (gate != null) {
                gate.close();
            }
            cancelDeadline();
            try {
                cancellation.cancel();
            } catch (Throwable failure) {
                recordCancellationCleanupFailure(failure);
            }
        }

        private synchronized void recordCancellationCleanupFailure(Throwable failure) {
            if (cancellationCleanupFailure == null) {
                cancellationCleanupFailure = failure;
            } else if (cancellationCleanupFailure != failure) {
                cancellationCleanupFailure.addSuppressed(failure);
            }
        }

        private synchronized Throwable takeCancellationCleanupFailure() {
            Throwable failure = cancellationCleanupFailure;
            cancellationCleanupFailure = null;
            return failure;
        }

        private synchronized boolean claimTerminalObservation() {
            if (terminalObservationClaimed) {
                return false;
            }
            terminalObservationClaimed = true;
            return true;
        }

        private boolean installPhysicalAttempt(
                AiProvider provider, AiPhysicalAttemptClientGrantGate gate) {
            AiProvider checkedProvider = Objects.requireNonNull(provider, "provider");
            AiPhysicalAttemptClientGrantGate checkedGate = Objects.requireNonNull(gate, "gate");
            boolean installed;
            synchronized (this) {
                installed = !cancellationStarted && physicalAttemptGate == null;
                if (installed) {
                    preparedProvider = checkedProvider;
                    physicalAttemptGate = checkedGate;
                }
            }
            if (!installed) {
                checkedGate.close();
            }
            return installed;
        }

        private synchronized AiProvider preparedProvider() {
            return preparedProvider;
        }

        private synchronized AiPhysicalAttemptClientGrantGate physicalAttemptGate() {
            return physicalAttemptGate;
        }

        private synchronized boolean matchesPhysicalAttemptIdentity(
                io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity identity) {
            return physicalAttemptGate != null
                    && physicalAttemptGate.expectedIdentity().equals(
                            Objects.requireNonNull(identity, "identity"));
        }
    }
}
