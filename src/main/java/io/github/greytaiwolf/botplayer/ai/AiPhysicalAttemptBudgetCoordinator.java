package io.github.greytaiwolf.botplayer.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Server-thread owner for the distributed physical-attempt budget handshake.
 *
 * <p>The narrow state machine is {@code OFFERED -> SETTLED/START_GRANTED -> closed}. Offering
 * reserves one local token accounting capability but does not settle it. An exact client prepare
 * ACK is the only transition that may call {@link AiTokenBudgetLedger#settleAttempt}; a successful
 * settlement returns one replay-stable {@link AiPhysicalAttemptStartGrant}. The grant means that
 * the server committed the attempt budget, not that a client HTTP call actually started.
 *
 * <p>This class owns no network handler, client credential, Provider, Scheduler, Retry wrapper,
 * lifecycle hook, Skill, Action, or Minecraft object. A future bridge must pass only the exact
 * active server dispatch and trusted {@link AiRetryAttemptBudgetContext}, send the DTOs unchanged,
 * and use {@link #closeExact(AiPhysicalAttemptIdentity)} or {@link #closeAll()} for its exact
 * lifecycle cleanup. In particular, terminal loss and disconnect after grant never refund a
 * settled attempt.
 */
public final class AiPhysicalAttemptBudgetCoordinator {
    private static final int MAX_ATTEMPT_ID_CANDIDATES = 16;
    /** Hard ceiling for active entries held by one pure server owner. */
    public static final int MAX_ACTIVE_ATTEMPTS = AiTokenBudgetPolicy.MAXIMUM_ACTIVE_RESERVATIONS;
    /** Hard ceiling for active attempts plus their replay tombstones. */
    public static final int MAX_RETAINED_ATTEMPT_IDENTITIES = 1_024;

    private final Thread ownerThread;
    private final Clock clock;
    private final Limits limits;
    private final Supplier<UUID> attemptIdSupplier;
    private final Map<AiRequestDispatchReceipt, ActiveAttempt> attemptsByReceipt =
            new LinkedHashMap<>();
    private final Map<UUID, ActiveAttempt> attemptsById = new LinkedHashMap<>();
    private final Map<UUID, Tombstone> tombstonesByAttemptId = new LinkedHashMap<>();

    private Instant lastObservedAt;

    /** Creates the bounded server owner with production UTC time. */
    public AiPhysicalAttemptBudgetCoordinator() {
        this(Clock.systemUTC(), Limits.defaults(), UUID::randomUUID);
    }

    /** Creates the bounded server owner with production UTC time and explicit capacity limits. */
    public AiPhysicalAttemptBudgetCoordinator(Limits limits) {
        this(Clock.systemUTC(), limits, UUID::randomUUID);
    }

    /** Package-private deterministic constructor for pure Java tests. */
    AiPhysicalAttemptBudgetCoordinator(
            Clock clock, Limits limits, Supplier<UUID> attemptIdSupplier) {
        ownerThread = Thread.currentThread();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.attemptIdSupplier = Objects.requireNonNull(
                attemptIdSupplier, "attemptIdSupplier");
        lastObservedAt = currentInstant();
    }

    /**
     * Reserves a future local ledger attempt and returns its redacted S2C offer.
     *
     * <p>Every rejection before reservation leaves this coordinator and the supplied ledger
     * unchanged. Callers must periodically invoke {@link #expireDueAttempts()} and
     * {@link #expireTombstones()} from their trusted server owner rather than relying on malformed
     * or replayed packets to mutate state.
     */
    public AiPhysicalAttemptOfferResult offer(AiPhysicalAttemptOfferRequest offerRequest) {
        requireOwnerThread();
        AiPhysicalAttemptOfferRequest request = Objects.requireNonNull(
                offerRequest, "offerRequest");
        AiClientRequestDispatch dispatch = request.dispatch();
        AiRetryAttemptBudgetContext budgetContext = request.budgetContext();
        if (!bindingMatchesDispatch(budgetContext.binding(), dispatch)) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.REQUEST_BINDING_MISMATCH);
        }
        if (request.attemptDeadline().isAfter(clientNotAfter(dispatch))) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.ATTEMPT_DEADLINE_EXCEEDS_CLIENT_NOT_AFTER);
        }

        AiRequestDispatchReceipt dispatchReceipt = AiRequestDispatchReceipt.fromDispatch(dispatch);
        if (attemptsByReceipt.containsKey(dispatchReceipt)) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.REQUEST_ATTEMPT_IN_FLIGHT);
        }

        Instant now = observeCurrentInstant();
        if (now == null) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.CLOCK_ROLLBACK);
        }
        if (epochMillis(now) >= dispatch.expiresAtEpochMillis()) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.CLIENT_NOT_AFTER_EXPIRED);
        }
        if (attemptsById.size() >= limits.maximumActiveAttempts()) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.ACTIVE_ATTEMPT_CAPACITY);
        }
        /* Reserve a tombstone slot now; a later close must never evict replay defense. */
        if (retainedIdentityCount() >= limits.maximumRetainedAttemptIdentities()) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.TOMBSTONE_CAPACITY);
        }

        UUID attemptId = firstAvailableAttemptId();
        if (attemptId == null) {
            return AiPhysicalAttemptOfferResult.rejected(
                    AiPhysicalAttemptOfferStatus.ATTEMPT_ID_EXHAUSTED);
        }
        AiTokenBudgetReservationResult reservationResult =
                budgetContext.reservePhysicalAttempt(request.attemptDeadline());
        if (!reservationResult.reserved()) {
            return AiPhysicalAttemptOfferResult.budgetRejected(reservationResult.status());
        }
        AiTokenReservation reservation = reservationResult.reservation().orElseThrow();
        AiPhysicalAttemptIdentity identity;
        try {
            identity = AiPhysicalAttemptIdentity.fromDispatch(
                    dispatch, attemptId, epochMillis(reservation.expiresAt()));
        } catch (RuntimeException exception) {
            try {
                budgetContext.ledger().release(reservation);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        ActiveAttempt active = new ActiveAttempt(identity, budgetContext, reservation);
        try {
            addActive(active);
        } catch (RuntimeException exception) {
            try {
                budgetContext.ledger().release(reservation);
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
        return AiPhysicalAttemptOfferResult.offered(
                new AiPhysicalAttemptOffer(identity));
    }

    /**
     * Accepts one exact C2S prepare ACK, settles once, and returns a replay-stable start grant.
     *
     * <p>A duplicate exact ACK for a granted attempt returns the same grant without calling the
     * ledger again. A drifted request, receipt, nonce, not-after value, or attempt id never reaches
     * the reservation and performs no state mutation.
     */
    public AiPhysicalAttemptPrepareResult acknowledge(
            AiPhysicalAttemptPrepareAck prepareAck) {
        requireOwnerThread();
        AiPhysicalAttemptIdentity identity = Objects.requireNonNull(
                prepareAck, "prepareAck").identity();
        ActiveAttempt active = attemptsById.get(identity.attemptId());
        if (active == null) {
            return absentPrepareResult(identity);
        }
        if (!active.identity.equals(identity)) {
            return AiPhysicalAttemptPrepareResult.rejected(
                    AiPhysicalAttemptPrepareStatus.IDENTITY_MISMATCH);
        }
        Instant now = observeCurrentInstant();
        if (now == null) {
            return AiPhysicalAttemptPrepareResult.rejected(
                    AiPhysicalAttemptPrepareStatus.CLOCK_ROLLBACK);
        }
        if (epochMillis(now) >= identity.clientNotAfterEpochMillis()) {
            if (active.startGrant == null) {
                closeUnstarted(active);
            } else {
                closeCommitted(active);
            }
            return AiPhysicalAttemptPrepareResult.rejected(
                    AiPhysicalAttemptPrepareStatus.CLIENT_NOT_AFTER_EXPIRED);
        }
        if (epochMillis(now) >= identity.physicalStartNotAfterEpochMillis()) {
            if (active.startGrant == null) {
                closeUnstarted(active);
            } else {
                closeCommitted(active);
            }
            return AiPhysicalAttemptPrepareResult.rejected(
                    AiPhysicalAttemptPrepareStatus.PHYSICAL_START_NOT_AFTER_EXPIRED);
        }
        if (active.startGrant != null) {
            return AiPhysicalAttemptPrepareResult.granted(active.startGrant);
        }
        if (!now.isBefore(active.reservation.expiresAt())) {
            closeUnstarted(active);
            return AiPhysicalAttemptPrepareResult.rejected(
                    AiPhysicalAttemptPrepareStatus.OFFER_EXPIRED);
        }

        AiTokenBudgetOperationStatus settlement;
        try {
            settlement = active.budgetContext.ledger().settleAttempt(active.reservation);
        } catch (RuntimeException exception) {
            closeUnstartedAfterSettlementFailure(active, exception);
            throw exception;
        }
        if (settlement != AiTokenBudgetOperationStatus.SETTLED) {
            closeUnstartedAfterSettlementFailure(active, null);
            return AiPhysicalAttemptPrepareResult.settlementRejected(settlement);
        }
        active.startGrant = new AiPhysicalAttemptStartGrant(active.identity);
        return AiPhysicalAttemptPrepareResult.granted(active.startGrant);
    }

    /**
     * Closes only an exact active attempt.
     *
     * <p>An offered attempt releases its unstarted reservation. A granted attempt is only removed
     * and tombstoned: it never calls release and therefore never refunds committed budget.
     */
    public AiPhysicalAttemptCloseResult closeExact(AiPhysicalAttemptIdentity identity) {
        requireOwnerThread();
        AiPhysicalAttemptIdentity checked = Objects.requireNonNull(identity, "identity");
        ActiveAttempt active = attemptsById.get(checked.attemptId());
        if (active == null) {
            return absentCloseResult(checked);
        }
        if (!active.identity.equals(checked)) {
            return AiPhysicalAttemptCloseResult.rejected(
                    AiPhysicalAttemptCloseStatus.IDENTITY_MISMATCH);
        }
        if (active.startGrant == null) {
            return AiPhysicalAttemptCloseResult.closedUnstarted(closeUnstarted(active));
        }
        closeCommitted(active);
        return AiPhysicalAttemptCloseResult.closedCommitted();
    }

    /**
     * Closes every active attempt for disconnect or lifecycle teardown.
     *
     * <p>Only pre-commit offers are released. Already granted entries are tombstoned with their
     * committed ledger accounting unchanged, even when the client never received the grant or a
     * terminal report.
     */
    public AiPhysicalAttemptCloseSummary closeAll() {
        requireOwnerThread();
        List<ActiveAttempt> activeAttempts = List.copyOf(attemptsById.values());
        int closedUnstarted = 0;
        int closedCommitted = 0;
        for (ActiveAttempt active : activeAttempts) {
            if (active.startGrant == null) {
                closeUnstarted(active);
                closedUnstarted++;
            } else {
                closeCommitted(active);
                closedCommitted++;
            }
        }
        return new AiPhysicalAttemptCloseSummary(closedUnstarted, closedCommitted);
    }

    /**
     * Closes every attempt that can no longer be used by its exact current client dispatch.
     *
     * <p>An offered entry expires at its server-side reservation boundary and releases only that
     * unstarted reservation. A granted entry expires at its physical-start not-after boundary and
     * is tombstoned without release: a grant is not HTTP-start evidence, but it is already the
     * server's no-refund commitment. Trusted lifecycle/session maintenance must call this method
     * periodically so a lost grant cannot retain an active slot forever.
     */
    public AiPhysicalAttemptCloseSummary expireDueAttempts() {
        return expireDueAttemptIdentities().summary();
    }

    /**
     * Expires unusable attempts and returns their exact identities for lifecycle correlation
     * cleanup.
     *
     * <p>The returned identities are safe only for exact local bookkeeping; they do not report a
     * factual Provider start, HTTP outcome, token usage, or billing event. A lifecycle bridge
     * must still close the matching gate/ticket by the complete receipt rather than infer a bot
     * request from an attempt id.
     */
    public ExpiredAttempts expireDueAttemptIdentities() {
        requireOwnerThread();
        Instant now = requireMonotonicCurrentInstant();
        long nowEpochMillis = epochMillis(now);
        List<ActiveAttempt> expiredUnstarted = new ArrayList<>();
        List<ActiveAttempt> expiredCommitted = new ArrayList<>();
        for (ActiveAttempt active : attemptsById.values()) {
            if (active.startGrant == null) {
                if (!now.isBefore(active.reservation.expiresAt())) {
                    expiredUnstarted.add(active);
                }
            } else if (nowEpochMillis >= active.identity.physicalStartNotAfterEpochMillis()) {
                expiredCommitted.add(active);
            }
        }
        expiredUnstarted.forEach(this::closeUnstarted);
        expiredCommitted.forEach(this::closeCommitted);
        List<AiPhysicalAttemptIdentity> identities = new ArrayList<>(
                expiredUnstarted.size() + expiredCommitted.size());
        expiredUnstarted.forEach(active -> identities.add(active.identity));
        expiredCommitted.forEach(active -> identities.add(active.identity));
        return new ExpiredAttempts(
                List.copyOf(identities),
                new AiPhysicalAttemptCloseSummary(
                        expiredUnstarted.size(), expiredCommitted.size()));
    }

    /** Exact expiry bookkeeping returned only to the trusted server-thread lifecycle owner. */
    public record ExpiredAttempts(
            List<AiPhysicalAttemptIdentity> identities,
            AiPhysicalAttemptCloseSummary summary) {
        public ExpiredAttempts {
            identities = List.copyOf(Objects.requireNonNull(identities, "identities"));
            summary = Objects.requireNonNull(summary, "summary");
        }
    }

    /**
     * Drops replay tombstones only after their own client not-after boundary has passed.
     *
     * <p>Active entries reserve their eventual tombstone slots at offer time, so this method never
     * evicts a still-valid replay defense to admit new work.
     */
    public int expireTombstones() {
        requireOwnerThread();
        long nowEpochMillis = epochMillis(requireMonotonicCurrentInstant());
        int removed = 0;
        Iterator<Tombstone> iterator = tombstonesByAttemptId.values().iterator();
        while (iterator.hasNext()) {
            Tombstone tombstone = iterator.next();
            if (nowEpochMillis >= tombstone.identity.clientNotAfterEpochMillis()) {
                iterator.remove();
                removed++;
            }
        }
        return removed;
    }

    /** Returns a bounded diagnostic with no client or request identity. */
    public AiPhysicalAttemptBudgetSnapshot snapshot() {
        requireOwnerThread();
        int committed = 0;
        for (ActiveAttempt active : attemptsById.values()) {
            if (active.startGrant != null) {
                committed++;
            }
        }
        return new AiPhysicalAttemptBudgetSnapshot(
                attemptsById.size() - committed, committed, tombstonesByAttemptId.size());
    }

    /** Bound configuration for one server-owned coordinator. */
    public record Limits(
            int maximumActiveAttempts, int maximumRetainedAttemptIdentities) {
        public Limits {
            if (maximumActiveAttempts < 1
                    || maximumActiveAttempts > MAX_ACTIVE_ATTEMPTS) {
                throw new IllegalArgumentException(
                        "maximumActiveAttempts must be between 1 and "
                                + MAX_ACTIVE_ATTEMPTS);
            }
            if (maximumRetainedAttemptIdentities < maximumActiveAttempts
                    || maximumRetainedAttemptIdentities
                    > MAX_RETAINED_ATTEMPT_IDENTITIES) {
                throw new IllegalArgumentException(
                        "maximumRetainedAttemptIdentities must be between "
                                + "maximumActiveAttempts and "
                                + MAX_RETAINED_ATTEMPT_IDENTITIES);
            }
        }

        public static Limits defaults() {
            return new Limits(4, 256);
        }
    }

    /** No client identity, token scope, nonce, prompt, or grant is exposed through diagnostics. */
    @Override
    public String toString() {
        return "AiPhysicalAttemptBudgetCoordinator[ownerThreadBound=true, activeAttempts="
                + attemptsById.size() + ", tombstones=" + tombstonesByAttemptId.size() + "]";
    }

    private AiPhysicalAttemptPrepareResult absentPrepareResult(
            AiPhysicalAttemptIdentity identity) {
        Tombstone tombstone = tombstonesByAttemptId.get(identity.attemptId());
        if (tombstone != null) {
            return AiPhysicalAttemptPrepareResult.rejected(
                    tombstone.identity.equals(identity)
                            ? AiPhysicalAttemptPrepareStatus.ATTEMPT_TOMBSTONED
                            : AiPhysicalAttemptPrepareStatus.IDENTITY_MISMATCH);
        }
        ActiveAttempt sameRequest = attemptsByReceipt.get(identity.dispatchReceipt());
        return AiPhysicalAttemptPrepareResult.rejected(sameRequest == null
                ? AiPhysicalAttemptPrepareStatus.NO_ACTIVE_ATTEMPT
                : AiPhysicalAttemptPrepareStatus.IDENTITY_MISMATCH);
    }

    private AiPhysicalAttemptCloseResult absentCloseResult(
            AiPhysicalAttemptIdentity identity) {
        Tombstone tombstone = tombstonesByAttemptId.get(identity.attemptId());
        if (tombstone != null) {
            return AiPhysicalAttemptCloseResult.rejected(
                    tombstone.identity.equals(identity)
                            ? AiPhysicalAttemptCloseStatus.ATTEMPT_TOMBSTONED
                            : AiPhysicalAttemptCloseStatus.IDENTITY_MISMATCH);
        }
        ActiveAttempt sameRequest = attemptsByReceipt.get(identity.dispatchReceipt());
        return AiPhysicalAttemptCloseResult.rejected(sameRequest == null
                ? AiPhysicalAttemptCloseStatus.NO_ACTIVE_ATTEMPT
                : AiPhysicalAttemptCloseStatus.IDENTITY_MISMATCH);
    }

    private void addActive(ActiveAttempt active) {
        ActiveAttempt priorAttempt = attemptsById.putIfAbsent(active.identity.attemptId(), active);
        if (priorAttempt != null) {
            throw new IllegalStateException("physical attempt id index is already occupied");
        }
        ActiveAttempt priorReceipt = attemptsByReceipt.putIfAbsent(
                active.identity.dispatchReceipt(), active);
        if (priorReceipt != null) {
            attemptsById.remove(active.identity.attemptId(), active);
            throw new IllegalStateException("physical dispatch receipt index is already occupied");
        }
    }

    private AiTokenBudgetOperationStatus closeUnstarted(ActiveAttempt active) {
        recordTombstoneThenRemove(active);
        return active.budgetContext.ledger().release(active.reservation);
    }

    private void closeUnstartedAfterSettlementFailure(
            ActiveAttempt active, RuntimeException primaryFailure) {
        try {
            closeUnstarted(active);
        } catch (RuntimeException cleanupFailure) {
            if (primaryFailure != null) {
                primaryFailure.addSuppressed(cleanupFailure);
            } else {
                throw cleanupFailure;
            }
        }
    }

    private void closeCommitted(ActiveAttempt active) {
        if (active.startGrant == null) {
            throw new IllegalStateException("an unstarted attempt cannot close as committed");
        }
        recordTombstoneThenRemove(active);
    }

    private void recordTombstoneThenRemove(ActiveAttempt active) {
        Tombstone tombstone = new Tombstone(active.identity);
        Tombstone prior = tombstonesByAttemptId.putIfAbsent(
                active.identity.attemptId(), tombstone);
        if (prior != null) {
            throw new IllegalStateException("physical attempt tombstone index is inconsistent");
        }
        if (!attemptsById.remove(active.identity.attemptId(), active)
                || !attemptsByReceipt.remove(active.identity.dispatchReceipt(), active)) {
            throw new IllegalStateException("physical attempt active indexes are inconsistent");
        }
    }

    private boolean bindingMatchesDispatch(
            AiTokenBudgetRequestBinding binding, AiClientRequestDispatch dispatch) {
        AiTokenBudgetRequestBinding checkedBinding = Objects.requireNonNull(binding, "binding");
        AiClientRequestDispatch checkedDispatch = Objects.requireNonNull(dispatch, "dispatch");
        AiTokenBudgetScope scope = checkedBinding.scope();
        return scope.ownerId().equals(checkedDispatch.ownerId())
                && scope.botId().equals(checkedDispatch.botId())
                && scope.agentId().equals(checkedDispatch.agentId())
                && checkedBinding.requestId().equals(checkedDispatch.requestId())
                && checkedBinding.revision() == checkedDispatch.revision();
    }

    private int retainedIdentityCount() {
        return Math.addExact(attemptsById.size(), tombstonesByAttemptId.size());
    }

    private UUID firstAvailableAttemptId() {
        List<UUID> candidates = new ArrayList<>(MAX_ATTEMPT_ID_CANDIDATES);
        for (int index = 0; index < MAX_ATTEMPT_ID_CANDIDATES; index++) {
            UUID candidate = attemptIdSupplier.get();
            if (candidate != null && !isZero(candidate) && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        for (UUID candidate : candidates) {
            if (!attemptsById.containsKey(candidate)
                    && !tombstonesByAttemptId.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private Instant observeCurrentInstant() {
        Instant current = currentInstant();
        if (current.isBefore(lastObservedAt)) {
            return null;
        }
        lastObservedAt = current;
        return current;
    }

    private Instant requireMonotonicCurrentInstant() {
        Instant current = observeCurrentInstant();
        if (current == null) {
            throw new IllegalStateException("physical attempt coordinator clock moved backward");
        }
        return current;
    }

    private Instant currentInstant() {
        return AiChecks.instant(clock.instant(), "clock instant");
    }

    private static Instant clientNotAfter(AiClientRequestDispatch dispatch) {
        return Instant.ofEpochMilli(Objects.requireNonNull(dispatch, "dispatch")
                .expiresAtEpochMillis());
    }

    private static long epochMillis(Instant instant) {
        Instant checked = AiChecks.instant(instant, "instant");
        try {
            return checked.toEpochMilli();
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "physical attempt budget coordinator must run on its owner thread");
        }
    }

    private static boolean isZero(UUID value) {
        return value.getMostSignificantBits() == 0L && value.getLeastSignificantBits() == 0L;
    }

    private static final class ActiveAttempt {
        private final AiPhysicalAttemptIdentity identity;
        private final AiRetryAttemptBudgetContext budgetContext;
        private final AiTokenReservation reservation;
        private AiPhysicalAttemptStartGrant startGrant;

        private ActiveAttempt(
                AiPhysicalAttemptIdentity identity,
                AiRetryAttemptBudgetContext budgetContext,
                AiTokenReservation reservation) {
            this.identity = Objects.requireNonNull(identity, "identity");
            this.budgetContext = Objects.requireNonNull(budgetContext, "budgetContext");
            this.reservation = Objects.requireNonNull(reservation, "reservation");
        }
    }

    private record Tombstone(AiPhysicalAttemptIdentity identity) {
        private Tombstone {
            identity = Objects.requireNonNull(identity, "identity");
        }
    }
}
