package io.github.greytaiwolf.botplayer.ai;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Concurrent-safe, bounded reservation accounting for one {@link AiTokenBudgetScope}.
 *
 * <p>This is deliberately only an accounting contract. It neither starts a Provider nor calls a
 * scheduler, client/session coordinator, network, lifecycle, Skill, Action or Minecraft API. A
 * future physical-attempt adapter must reserve a fresh token, call {@link #settleAttempt} before
 * its real remote invocation, and never refund a settled attempt.</p>
 */
public final class AiTokenBudgetLedger implements AutoCloseable {
    private static final int MAX_RESERVATION_ID_CANDIDATES = 16;

    private final AiTokenBudgetScope scope;
    private final AiTokenBudgetPolicy policy;
    private final Clock clock;
    private final Supplier<UUID> reservationIdSupplier;
    private final Object lock = new Object();
    private final Map<UUID, AiTokenReservation> activeReservations =
            new LinkedHashMap<>();

    private Instant lastObservedAt;
    private long reservedTokens;
    private long committedTokens;
    private boolean closed;

    public AiTokenBudgetLedger(
            AiTokenBudgetScope scope, AiTokenBudgetPolicy policy) {
        this(scope, policy, Clock.systemUTC(), UUID::randomUUID);
    }

    /** Package-private deterministic/adversarial constructor for pure Java tests. */
    AiTokenBudgetLedger(
            AiTokenBudgetScope scope,
            AiTokenBudgetPolicy policy,
            Clock clock,
            Supplier<UUID> reservationIdSupplier) {
        this.scope = Objects.requireNonNull(scope, "scope");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.reservationIdSupplier = Objects.requireNonNull(
                reservationIdSupplier, "reservationIdSupplier");
        this.lastObservedAt = currentInstant();
    }

    /**
     * Reserves the conservative admission total for one future physical attempt.
     *
     * <p>The binding must belong to this ledger's exact scope and the admission must already be
     * accepted by a trusted model-policy path. A rejection never spends a token. This overload
     * accepts only an explicit expiration; callers with a trusted deadline should use
     * {@link #reserveForDeadline(AiTokenBudgetRequestBinding, AiModelAdmission, Instant)} so the
     * ledger derives the bounded TTL itself.</p>
     */
    public AiTokenBudgetReservationResult reserve(
            AiTokenBudgetRequestBinding binding,
            AiModelAdmission admission,
            Instant expiresAt) {
        AiTokenBudgetRequestBinding checkedBinding = Objects.requireNonNull(
                binding, "binding");
        AiModelAdmission checkedAdmission = Objects.requireNonNull(
                admission, "admission");
        Instant checkedExpiresAt = AiChecks.instant(expiresAt, "expiresAt");
        if (!checkedAdmission.accepted()) {
            return rejected(AiTokenBudgetOperationStatus.ADMISSION_REJECTED);
        }
        if (!scope.equals(checkedBinding.scope())) {
            return rejected(AiTokenBudgetOperationStatus.SCOPE_MISMATCH);
        }
        if (checkedAdmission.reservedTotalTokens() <= 0L) {
            return rejected(AiTokenBudgetOperationStatus.INVALID_ADMISSION);
        }
        Instant observedAt = currentInstant();
        return reserveAt(checkedBinding, checkedAdmission, observedAt, checkedExpiresAt);
    }

    /**
     * Reserves one future physical attempt until the earlier of a trusted deadline and this
     * ledger's maximum reservation age.
     *
     * <p>This is the only public helper that derives an expiration from the ledger's own clock
     * and policy. A future retry adapter must pass its already-trusted logical-attempt deadline;
     * it must not guess a TTL or retain a reservation past that deadline. The returned reservation
     * is still not permission to invoke a Provider: {@link #settleAttempt(AiTokenReservation)}
     * must win immediately before that physical invocation.
     */
    public AiTokenBudgetReservationResult reserveForDeadline(
            AiTokenBudgetRequestBinding binding,
            AiModelAdmission admission,
            Instant deadline) {
        AiTokenBudgetRequestBinding checkedBinding = Objects.requireNonNull(
                binding, "binding");
        AiModelAdmission checkedAdmission = Objects.requireNonNull(
                admission, "admission");
        Instant checkedDeadline = AiChecks.instant(deadline, "deadline");
        if (!checkedAdmission.accepted()) {
            return rejected(AiTokenBudgetOperationStatus.ADMISSION_REJECTED);
        }
        if (!scope.equals(checkedBinding.scope())) {
            return rejected(AiTokenBudgetOperationStatus.SCOPE_MISMATCH);
        }
        if (checkedAdmission.reservedTotalTokens() <= 0L) {
            return rejected(AiTokenBudgetOperationStatus.INVALID_ADMISSION);
        }
        Instant observedAt = currentInstant();
        Instant expiresAt = expirationForDeadline(observedAt, checkedDeadline);
        return reserveAt(checkedBinding, checkedAdmission, observedAt, expiresAt);
    }

    private AiTokenBudgetReservationResult reserveAt(
            AiTokenBudgetRequestBinding binding,
            AiModelAdmission admission,
            Instant observedAt,
            Instant expiresAt) {
        if (!isValidExpiration(observedAt, expiresAt)) {
            return rejected(AiTokenBudgetOperationStatus.INVALID_EXPIRATION);
        }
        List<UUID> candidates = reservationIdCandidates();
        synchronized (lock) {
            if (!observeLocked(observedAt)) {
                return rejected(AiTokenBudgetOperationStatus.CLOCK_ROLLBACK);
            }
            expireDueLocked(observedAt);
            if (closed) {
                return rejected(AiTokenBudgetOperationStatus.LEDGER_CLOSED);
            }
            if (activeReservations.size() >= policy.maximumActiveReservations()) {
                return rejected(AiTokenBudgetOperationStatus.ACTIVE_RESERVATION_LIMIT);
            }
            long total = admission.reservedTotalTokens();
            if (total > availableTokensLocked()) {
                return rejected(AiTokenBudgetOperationStatus.TOKEN_BUDGET_EXHAUSTED);
            }
            UUID reservationId = firstAvailableCandidateLocked(candidates);
            if (reservationId == null) {
                return rejected(AiTokenBudgetOperationStatus.RESERVATION_ID_EXHAUSTED);
            }
            AiTokenReservation reservation = new AiTokenReservation(
                    reservationId,
                    binding,
                    admission.estimatedInputTokens(),
                    admission.reservedOutputTokens(),
                    total,
                    observedAt,
                    expiresAt);
            addReservationLocked(reservation);
            return new AiTokenBudgetReservationResult(
                    AiTokenBudgetOperationStatus.RESERVED,
                    Optional.of(reservation));
        }
    }

    /**
     * Releases one exact, not-yet-settled reservation before any remote invocation.
     *
     * <p>This operation deliberately does not consult the clock: releasing an attempt that is known
     * not to have started is always at least as conservative as waiting for its expiry.</p>
     */
    public AiTokenBudgetOperationStatus release(AiTokenReservation reservation) {
        AiTokenReservation checkedReservation = Objects.requireNonNull(
                reservation, "reservation");
        synchronized (lock) {
            AiTokenReservation current = activeReservations.get(
                    checkedReservation.reservationId());
            if (current == null) {
                return AiTokenBudgetOperationStatus.NOT_FOUND;
            }
            if (current != checkedReservation) {
                return AiTokenBudgetOperationStatus.STALE_RESERVATION;
            }
            removeExactReservationLocked(current);
            return AiTokenBudgetOperationStatus.RELEASED;
        }
    }

    /**
     * Atomically consumes one exact reservation immediately before a physical remote attempt.
     *
     * <p>After this returns {@link AiTokenBudgetOperationStatus#SETTLED}, no response, Provider
     * failure, cancellation, timeout or provider-reported usage can refund the committed total.</p>
     */
    public AiTokenBudgetOperationStatus settleAttempt(AiTokenReservation reservation) {
        AiTokenReservation checkedReservation = Objects.requireNonNull(
                reservation, "reservation");
        Instant observedAt = currentInstant();
        synchronized (lock) {
            if (!observeLocked(observedAt)) {
                return AiTokenBudgetOperationStatus.CLOCK_ROLLBACK;
            }
            AiTokenReservation current = activeReservations.get(
                    checkedReservation.reservationId());
            if (current == null) {
                return AiTokenBudgetOperationStatus.NOT_FOUND;
            }
            if (current != checkedReservation) {
                return AiTokenBudgetOperationStatus.STALE_RESERVATION;
            }
            if (!observedAt.isBefore(current.expiresAt())) {
                removeExactReservationLocked(current);
                expireDueLocked(observedAt);
                return AiTokenBudgetOperationStatus.EXPIRED;
            }
            expireDueLocked(observedAt);
            long nextCommitted = checkedAdd(committedTokens,
                    current.reservedTotalTokens(), "committed token accounting");
            long remainingReserved = reservedTokens - current.reservedTotalTokens();
            if (remainingReserved < 0L
                    || nextCommitted > policy.maximumTokens() - remainingReserved) {
                throw new IllegalStateException("token reservation accounting is inconsistent");
            }
            removeExactReservationLocked(current);
            committedTokens = nextCommitted;
            return AiTokenBudgetOperationStatus.SETTLED;
        }
    }

    /** Expires every still-unsettled reservation at its half-open deadline boundary. */
    public List<AiTokenReservation> expireDue() {
        Instant observedAt = currentInstant();
        synchronized (lock) {
            requireMonotonicClockLocked(observedAt);
            return List.copyOf(expireDueLocked(observedAt));
        }
    }

    /** Returns a bounded diagnostic snapshot after applying due expiries. */
    public AiTokenBudgetSnapshot snapshot() {
        Instant observedAt = currentInstant();
        synchronized (lock) {
            requireMonotonicClockLocked(observedAt);
            expireDueLocked(observedAt);
            return new AiTokenBudgetSnapshot(
                    policy.maximumTokens(),
                    reservedTokens,
                    committedTokens,
                    activeReservations.size(),
                    closed,
                    observedAt);
        }
    }

    /**
     * Releases only not-yet-settled reservations and permanently rejects later reservations.
     * Committed accounting remains available through {@link #snapshot()}.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            activeReservations.clear();
            reservedTokens = 0L;
            closed = true;
        }
    }

    /** Safe diagnostic summary with no scope, request or reservation identity. */
    @Override
    public String toString() {
        synchronized (lock) {
            return "AiTokenBudgetLedger[maximumTokens=" + policy.maximumTokens()
                    + ", reservedTokens=" + reservedTokens
                    + ", committedTokens=" + committedTokens
                    + ", activeReservations=" + activeReservations.size()
                    + ", closed=" + closed + "]";
        }
    }

    private static AiTokenBudgetReservationResult rejected(
            AiTokenBudgetOperationStatus status) {
        return new AiTokenBudgetReservationResult(status, Optional.empty());
    }

    private Instant currentInstant() {
        return AiChecks.instant(clock.instant(), "clock instant");
    }

    private boolean isValidExpiration(Instant observedAt, Instant expiresAt) {
        if (!expiresAt.isAfter(observedAt)) {
            return false;
        }
        try {
            return !expiresAt.isAfter(observedAt.plus(
                    policy.maximumReservationAge()));
        } catch (DateTimeException | ArithmeticException exception) {
            // The bounded age extends beyond Instant.MAX, so every representable later deadline
            // remains within it. Rejecting it would make accounting depend on representability,
            // rather than on an unsafe longer TTL.
            return true;
        }
    }

    private Instant expirationForDeadline(Instant observedAt, Instant deadline) {
        try {
            Instant maximumExpiry = observedAt.plus(policy.maximumReservationAge());
            return deadline.isBefore(maximumExpiry) ? deadline : maximumExpiry;
        } catch (DateTimeException | ArithmeticException exception) {
            // The conceptual bounded expiry lies beyond Instant.MAX. A valid Instant deadline is
            // necessarily earlier, and reserveAt still rejects a deadline at or before now.
            return deadline;
        }
    }

    private List<UUID> reservationIdCandidates() {
        List<UUID> candidates = new ArrayList<>(MAX_RESERVATION_ID_CANDIDATES);
        for (int index = 0; index < MAX_RESERVATION_ID_CANDIDATES; index++) {
            UUID candidate = reservationIdSupplier.get();
            if (candidate != null && !isZero(candidate)
                    && !candidates.contains(candidate)) {
                candidates.add(candidate);
            }
        }
        return List.copyOf(candidates);
    }

    private UUID firstAvailableCandidateLocked(List<UUID> candidates) {
        for (UUID candidate : candidates) {
            if (!activeReservations.containsKey(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean observeLocked(Instant observedAt) {
        if (observedAt.isBefore(lastObservedAt)) {
            return false;
        }
        lastObservedAt = observedAt;
        return true;
    }

    private void requireMonotonicClockLocked(Instant observedAt) {
        if (!observeLocked(observedAt)) {
            throw new IllegalStateException("token budget clock moved backwards");
        }
    }

    private List<AiTokenReservation> expireDueLocked(Instant observedAt) {
        List<AiTokenReservation> expired = new ArrayList<>();
        Iterator<Map.Entry<UUID, AiTokenReservation>> iterator = activeReservations
                .entrySet().iterator();
        while (iterator.hasNext()) {
            AiTokenReservation reservation = iterator.next().getValue();
            if (!observedAt.isBefore(reservation.expiresAt())) {
                iterator.remove();
                subtractReservedTokensLocked(reservation.reservedTotalTokens());
                expired.add(reservation);
            }
        }
        return expired;
    }

    private long availableTokensLocked() {
        long used = checkedAdd(reservedTokens, committedTokens,
                "token accounting");
        if (used > policy.maximumTokens()) {
            throw new IllegalStateException("token accounting exceeds its policy maximum");
        }
        return policy.maximumTokens() - used;
    }

    private void addReservationLocked(AiTokenReservation reservation) {
        long nextReserved = checkedAdd(reservedTokens,
                reservation.reservedTotalTokens(), "reserved token accounting");
        if (nextReserved > policy.maximumTokens() - committedTokens) {
            throw new IllegalStateException("token reservation exceeds its policy maximum");
        }
        if (activeReservations.putIfAbsent(reservation.reservationId(), reservation) != null) {
            throw new IllegalStateException("token reservation id collision");
        }
        reservedTokens = nextReserved;
    }

    private void removeExactReservationLocked(AiTokenReservation reservation) {
        if (!activeReservations.remove(reservation.reservationId(), reservation)) {
            throw new IllegalStateException("token reservation index is inconsistent");
        }
        subtractReservedTokensLocked(reservation.reservedTotalTokens());
    }

    private void subtractReservedTokensLocked(long tokens) {
        if (reservedTokens < tokens) {
            throw new IllegalStateException("reserved token accounting underflow");
        }
        reservedTokens -= tokens;
    }

    private static long checkedAdd(long left, long right, String name) {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(name + " overflow", exception);
        }
    }

    private static boolean isZero(UUID value) {
        return value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L;
    }
}
