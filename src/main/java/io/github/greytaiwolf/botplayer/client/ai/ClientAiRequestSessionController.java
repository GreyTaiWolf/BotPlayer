package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiProvider;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.CancellationTokenSource;
import io.github.greytaiwolf.botplayer.ai.review.AiReviewOnlyContract;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
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
import java.util.concurrent.RejectedExecutionException;
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

    /**
     * Physical-client handoff for a completed proposal.
     *
     * <p>An asynchronous handoff must call {@link BindingEpochHandoff#release()} exactly once
     * after it sends or drops the proposal. The lease remains valid only while the same bot's
     * local credential binding epoch is unchanged.
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
    private final Map<UUID, ActiveSession> sessionsByRequest = new LinkedHashMap<>();
    private final Map<UUID, ActiveSession> sessionsByBot = new LinkedHashMap<>();
    private final Map<UUID, Long> tombstoneExpiryByRequest = new LinkedHashMap<>();
    /* Entries exist only while a session or queued physical-client handoff retains them. */
    private final Map<UUID, BotBindingEpoch> bindingEpochsByBot = new LinkedHashMap<>();

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
                synchronousProposalHandoff(proposalSink));
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
    }

    /**
     * Rebinds this controller to a newly connected local owner and cancels every prior session.
     */
    public void updateLocalOwner(UUID ownerId) {
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
        List<ActiveSession> cancelled;
        synchronized (lock) {
            localOwnerId = null;
            cancelled = detachAllLocked();
            tombstoneExpiryByRequest.clear();
        }
        cancelAll(cancelled);
    }

    /**
     * Starts one local Provider request only after exact local binding checks.
     *
     * <p>Duplicate request ids never execute twice. A newer request for the same bot retires and
     * cancels the older local request before the newer Provider call begins.
     */
    public ClientAiRequestDispatchStatus accept(AiClientRequestDispatch dispatch) {
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        if (checked.purpose() == AiRequestPurpose.REVIEW_ONLY_V1
                && !AiReviewOnlyContract.isCanonicalDispatch(checked)) {
            return ClientAiRequestDispatchStatus.REVIEW_CONTRACT_REJECTED;
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
        cancelAll(cancelled);

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
        } catch (RejectedExecutionException exception) {
            cancelSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        } catch (RuntimeException exception) {
            cancelSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
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
            cancelSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
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
            cancelSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        }

        try {
            completion.whenComplete((response, failure) ->
                    completeSession(session, response, failure));
        } catch (RuntimeException exception) {
            cancelSession(session);
            return ClientAiRequestDispatchStatus.PROVIDER_UNAVAILABLE;
        }
        return ClientAiRequestDispatchStatus.STARTED;
    }

    /** Cancels a still-active local request by its server-generated request id. */
    public boolean cancelRequest(UUID requestId) {
        Objects.requireNonNull(requestId, "requestId");
        ActiveSession session;
        synchronized (lock) {
            session = sessionsByRequest.get(requestId);
            if (session == null) {
                return false;
            }
            detachLocked(session);
        }
        session.cancel();
        return true;
    }

    /**
     * Applies a server-originated cancellation only when it matches the exact active dispatch and
     * the current physical-client epoch is still live. A stale packet is a no-op rather than a
     * broad bot/request-id cancellation.
     */
    public boolean cancel(AiRequestCancellationPayload cancellation) {
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
        session.cancel();
        return true;
    }

    /** Cancels the one active local request for a bot, if any. */
    public boolean cancelBot(UUID botId) {
        Objects.requireNonNull(botId, "botId");
        ActiveSession session;
        synchronized (lock) {
            session = sessionsByBot.get(botId);
            if (session == null) {
                return false;
            }
            detachLocked(session);
        }
        session.cancel();
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
        List<ActiveSession> cancelled;
        synchronized (lock) {
            cancelled = detachAllLocked();
        }
        cancelAll(cancelled);
        return cancelled.size();
    }

    /** Expires requests against a testable wall-clock source; returns the number retired. */
    public int expireThrough(long nowEpochMillis) {
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
        session.cancel();
    }

    private void completeSession(
            ActiveSession session, AiResponse response, Throwable failure) {
        if (failure != null
                || response == null
                || !isAcceptableResponse(session.dispatch, response)) {
            finishSession(session);
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
                finishSession(session);
                return;
            }
        } catch (RuntimeException exception) {
            finishSession(session);
            return;
        }

        /*
         * Keep the session current until the non-blocking physical-client handoff has been made.
         * This makes a concurrent cancellation/owner change win before the C2S proposal is queued.
         */
        boolean finished;
        synchronized (lock) {
            if (!isCurrentLocked(session)
                    || session.cancellation.isCancellationRequested()
                    || !isBindingEpochCurrentLocked(session)
                    || localStatusLocked(
                            session.dispatch,
                            readCurrentEpochMillisSafely())
                    != ClientAiRequestDispatchStatus.STARTED) {
                finished = detachIfCurrentLocked(session);
            } else {
                BindingEpochHandoff bindingEpoch = createBindingEpochHandoffLocked(session);
                try {
                    proposalHandoff.accept(session.dispatch, proposal, bindingEpoch);
                } catch (RuntimeException exception) {
                    // A send handoff failure must not trigger a retry or reveal model output.
                    bindingEpoch.release();
                } finally {
                    finished = detachIfCurrentLocked(session);
                }
            }
        }
        if (finished) {
            /*
             * The provider has completed, but signalling the token still releases any provider
             * listener which races its terminal callback.  This is idempotent for the normal
             * success path and ensures failures/mismatches never retain a cancellation
             * registration or a transport-owned credential copy.
             */
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

    private void cancelSession(ActiveSession session) {
        synchronized (lock) {
            detachIfCurrentLocked(session);
        }
        // Idempotently notify even if a concurrent replacement detached the session first.
        session.cancel();
    }

    private void finishSession(ActiveSession session) {
        synchronized (lock) {
            detachIfCurrentLocked(session);
        }
        session.cancel();
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

    private static void cancelAll(List<ActiveSession> sessions) {
        sessions.forEach(ActiveSession::cancel);
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
        private long value;
        private int activeSessions;
        private int queuedHandoffs;

        private void advance() {
            value = value == Long.MAX_VALUE ? 1L : value + 1L;
        }
    }

    private static final class ActiveSession {
        private final AiClientRequestDispatch dispatch;
        private final BotBindingEpoch bindingEpoch;
        private final long bindingEpochValue;
        private final CancellationTokenSource cancellation = new CancellationTokenSource();
        private ScheduledFuture<?> deadline;

        private ActiveSession(
                AiClientRequestDispatch dispatch,
                BotBindingEpoch bindingEpoch,
                long bindingEpochValue) {
            this.dispatch = dispatch;
            this.bindingEpoch = bindingEpoch;
            this.bindingEpochValue = bindingEpochValue;
        }

        private synchronized void setDeadline(ScheduledFuture<?> deadline) {
            this.deadline = Objects.requireNonNull(deadline, "deadline");
            if (cancellation.isCancellationRequested()) {
                deadline.cancel(false);
            }
        }

        private synchronized void cancelDeadline() {
            if (deadline != null) {
                try {
                    deadline.cancel(false);
                } catch (RuntimeException ignored) {
                    // A third-party scheduler cannot prevent cancellation-token cleanup.
                } finally {
                    deadline = null;
                }
            }
        }

        private void cancel() {
            try {
                cancelDeadline();
            } finally {
                cancellation.cancel();
            }
        }
    }
}
