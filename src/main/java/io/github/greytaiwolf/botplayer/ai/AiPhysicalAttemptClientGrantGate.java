package io.github.greytaiwolf.botplayer.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;

/**
 * Client-local, one-shot fence for a server-committed physical-attempt start grant.
 *
 * <p>The gate is deliberately independent from the current client session controller. It neither
 * starts a Provider nor sends a packet; it invokes one injected local handoff at most once. A
 * future client bridge must create it only after it has accepted the matching offer, and its
 * handoff must call {@link LocalStartLease#tryClaimPhysicalStart()} immediately before one local
 * Provider or HTTP start. That atomic claim fences the exact dispatch identity, current local binding
 * epoch, active connection guard, and both client/physical-start deadlines again after the queue
 * boundary.
 */
public final class AiPhysicalAttemptClientGrantGate {
    private final Object lock = new Object();
    private final AiPhysicalAttemptIdentity expectedIdentity;
    private final long bindingEpoch;
    private final LongSupplier currentBindingEpoch;
    private final LongSupplier currentEpochMillis;
    private final BooleanSupplier localSessionActive;
    private final LocalStartHandoff handoff;

    private boolean handedOff;
    private boolean closed;
    private boolean clientDeadlineExpired;
    private boolean physicalStartDeadlineExpired;
    private boolean localClockRollback;
    private long highestObservedEpochMillis = -1L;
    private LocalStartLeaseImpl lease;

    /**
     * Creates one local gate for the exact offer and already locally verified dispatch binding.
     *
     * <p>The constructor checks only immutable correlation. Current epoch/session/deadline checks
     * run immediately before handoff and again through the lease's one-shot start claim, so a
     * queued handoff cannot use a grant after a local rebind or deadline boundary.
     */
    public AiPhysicalAttemptClientGrantGate(
            AiPhysicalAttemptOffer offer,
            AiClientRequestDispatch localDispatch,
            long bindingEpoch,
            LongSupplier currentBindingEpoch,
            LongSupplier currentEpochMillis,
            BooleanSupplier localSessionActive,
            LocalStartHandoff handoff) {
        AiPhysicalAttemptOffer checkedOffer = Objects.requireNonNull(offer, "offer");
        AiClientRequestDispatch checkedDispatch = Objects.requireNonNull(
                localDispatch, "localDispatch");
        if (!checkedOffer.identity().matches(checkedDispatch)) {
            throw new IllegalArgumentException(
                    "offer identity must exactly match the local dispatch");
        }
        if (bindingEpoch <= 0L) {
            throw new IllegalArgumentException("bindingEpoch must be positive");
        }
        expectedIdentity = checkedOffer.identity();
        this.bindingEpoch = bindingEpoch;
        this.currentBindingEpoch = Objects.requireNonNull(
                currentBindingEpoch, "currentBindingEpoch");
        this.currentEpochMillis = Objects.requireNonNull(
                currentEpochMillis, "currentEpochMillis");
        this.localSessionActive = Objects.requireNonNull(
                localSessionActive, "localSessionActive");
        this.handoff = Objects.requireNonNull(handoff, "handoff");
    }

    /**
     * Performs at most one local handoff for the exact server start grant.
     *
     * <p>The handoff is marked consumed before external code runs. Therefore a synchronous
     * re-entry, a duplicate S2C grant, or a throwing handoff cannot invoke it a second time. A
     * thrown handoff is deliberately propagated: it may already have queued local work, so retrying
     * would weaken the one-physical-handoff invariant.
     */
    public AiPhysicalAttemptClientGrantStatus accept(AiPhysicalAttemptStartGrant grant) {
        AiPhysicalAttemptStartGrant checkedGrant = Objects.requireNonNull(grant, "grant");
        LocalStartLeaseImpl handoffLease;
        synchronized (lock) {
            if (!expectedIdentity.equals(checkedGrant.identity())) {
                return AiPhysicalAttemptClientGrantStatus.IDENTITY_MISMATCH;
            }
            AiPhysicalAttemptClientGrantStatus timeFence = timeFenceStatusLocked();
            if (closed) {
                return AiPhysicalAttemptClientGrantStatus.CLOSED;
            }
            if (handedOff) {
                return AiPhysicalAttemptClientGrantStatus.ALREADY_HANDED_OFF;
            }
            if (timeFence != AiPhysicalAttemptClientGrantStatus.HANDED_OFF) {
                return timeFence;
            }
            AiPhysicalAttemptClientGrantStatus localFence = localSessionFenceStatusLocked();
            if (localFence != AiPhysicalAttemptClientGrantStatus.HANDED_OFF) {
                return localFence;
            }
            handedOff = true;
            handoffLease = new LocalStartLeaseImpl(checkedGrant.identity());
            lease = handoffLease;
        }
        handoff.accept(checkedGrant, handoffLease);
        return AiPhysicalAttemptClientGrantStatus.HANDED_OFF;
    }

    /**
     * Retires the client-local gate without reporting a factual terminal state to the server.
     *
     * <p>Closing after grant leaves server accounting committed; it retires every unclaimed local
     * lease and suppresses a later duplicate handoff. A successful physical-start claim is already
     * an irreversible local boundary and is deliberately not revoked or retried.
     */
    public void close() {
        synchronized (lock) {
            closed = true;
            if (lease != null) {
                lease.releaseLocked();
            }
        }
    }

    /** The exact identity is safe for a future transport encoder but intentionally redacted in logs. */
    public AiPhysicalAttemptIdentity expectedIdentity() {
        return expectedIdentity;
    }

    /** No dispatch, nonce, owner, request, or grant details are rendered. */
    @Override
    public String toString() {
        synchronized (lock) {
            return "AiPhysicalAttemptClientGrantGate[bound=true, handedOff="
                    + handedOff + ", closed=" + closed + "]";
        }
    }

    private AiPhysicalAttemptClientGrantStatus currentFenceStatusLocked() {
        AiPhysicalAttemptClientGrantStatus timeFence = timeFenceStatusLocked();
        if (timeFence != AiPhysicalAttemptClientGrantStatus.HANDED_OFF) {
            return timeFence;
        }
        return localSessionFenceStatusLocked();
    }

    private AiPhysicalAttemptClientGrantStatus localSessionFenceStatusLocked() {
        if (!localSessionActive.getAsBoolean()) {
            return AiPhysicalAttemptClientGrantStatus.LOCAL_SESSION_INACTIVE;
        }
        if (currentBindingEpoch.getAsLong() != bindingEpoch) {
            return AiPhysicalAttemptClientGrantStatus.LOCAL_BINDING_EPOCH_MISMATCH;
        }
        return AiPhysicalAttemptClientGrantStatus.HANDED_OFF;
    }

    /** Reads and permanently latches time fences before any mutable local-session predicate. */
    private AiPhysicalAttemptClientGrantStatus timeFenceStatusLocked() {
        long now = currentEpochMillis.getAsLong();
        if (clientDeadlineExpired) {
            return AiPhysicalAttemptClientGrantStatus.CLIENT_NOT_AFTER_EXPIRED;
        }
        if (physicalStartDeadlineExpired) {
            return AiPhysicalAttemptClientGrantStatus.PHYSICAL_START_NOT_AFTER_EXPIRED;
        }
        if (localClockRollback) {
            return AiPhysicalAttemptClientGrantStatus.LOCAL_CLOCK_ROLLBACK;
        }
        if (now < 0L || (highestObservedEpochMillis >= 0L
                && now < highestObservedEpochMillis)) {
            localClockRollback = true;
            return AiPhysicalAttemptClientGrantStatus.LOCAL_CLOCK_ROLLBACK;
        }
        highestObservedEpochMillis = now;
        if (now >= expectedIdentity.physicalStartNotAfterEpochMillis()) {
            physicalStartDeadlineExpired = true;
            return AiPhysicalAttemptClientGrantStatus.PHYSICAL_START_NOT_AFTER_EXPIRED;
        }
        if (now >= expectedIdentity.clientNotAfterEpochMillis()) {
            clientDeadlineExpired = true;
            return AiPhysicalAttemptClientGrantStatus.CLIENT_NOT_AFTER_EXPIRED;
        }
        return AiPhysicalAttemptClientGrantStatus.HANDED_OFF;
    }

    /**
     * Retained by exactly one local handoff. It must atomically claim the actual queued Provider
     * start boundary; no caller can use it to request another grant or reopen this gate.
     */
    public interface LocalStartLease {
        /**
         * Atomically consumes the one local physical-start permission when every local fence is
         * still current. A {@code true} result is the local physical-start linearization point;
         * callers must invoke their Provider/HTTP start exactly once immediately after it.
         */
        boolean tryClaimPhysicalStart();

        /** Idempotently retires this local queued handoff. */
        void release();
    }

    /** One-shot callback for a future local client queue; it is not an HTTP or packet API. */
    @FunctionalInterface
    public interface LocalStartHandoff {
        void accept(AiPhysicalAttemptStartGrant grant, LocalStartLease lease);
    }

    private final class LocalStartLeaseImpl implements LocalStartLease {
        private final AiPhysicalAttemptIdentity identity;
        private boolean released;
        private boolean startClaimed;

        private LocalStartLeaseImpl(AiPhysicalAttemptIdentity identity) {
            this.identity = Objects.requireNonNull(identity, "identity");
        }

        @Override
        public boolean tryClaimPhysicalStart() {
            synchronized (lock) {
                if (lease != this || released || startClaimed || closed || !handedOff
                        || !expectedIdentity.equals(identity)
                        || currentFenceStatusLocked()
                        != AiPhysicalAttemptClientGrantStatus.HANDED_OFF) {
                    return false;
                }
                startClaimed = true;
                return true;
            }
        }

        @Override
        public void release() {
            synchronized (lock) {
                releaseLocked();
            }
        }

        private void releaseLocked() {
            released = true;
        }
    }
}
