package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.AiModelAdmission;
import io.github.greytaiwolf.botplayer.ai.AiModelAdmissionStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptBudgetCoordinator;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptCloseResult;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOfferRequest;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOfferResult;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareAck;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareResult;
import io.github.greytaiwolf.botplayer.ai.AiRetryAttemptBudgetContext;
import io.github.greytaiwolf.botplayer.ai.AiTokenBudgetLedger;
import io.github.greytaiwolf.botplayer.ai.AiTokenBudgetPolicy;
import io.github.greytaiwolf.botplayer.ai.AiTokenBudgetRequestBinding;
import io.github.greytaiwolf.botplayer.ai.AiTokenBudgetScope;
import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import io.github.greytaiwolf.botplayer.ai.transport.AiRequestDispatchReceipt;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * R1-only server-owned accounting and correlation holder for one real owner.
 *
 * <p>This class deliberately has no network, Minecraft, Provider, scheduler, or lifecycle
 * authority. Its caller must first prove that a dispatch is the current canonical R1 ticket for
 * this owner. It then derives a conservative server-only admission and a per
 * {@code (owner, bot, agent)} bounded ledger before delegating the exact offer/ACK/close state
 * machine to {@link AiPhysicalAttemptBudgetCoordinator}. Client model capabilities, usage, and
 * clocks never enter this class.
 */
public final class AiReviewOnlyPhysicalAttemptOwner implements AutoCloseable {
    /** At most seven fixed R1 admissions may commit during one live owner/binding scope. */
    public static final long MAXIMUM_SCOPE_TOKENS = 16_128L;
    private static final Duration MAXIMUM_RESERVATION_AGE = Duration.ofSeconds(2L);
    private static final AiTokenBudgetPolicy LEDGER_POLICY = new AiTokenBudgetPolicy(
            MAXIMUM_SCOPE_TOKENS, 1, MAXIMUM_RESERVATION_AGE);

    private final UUID ownerId;
    private final AiPhysicalAttemptBudgetCoordinator coordinator;
    private final Map<AiTokenBudgetScope, AiTokenBudgetLedger> ledgersByScope =
            new LinkedHashMap<>();
    private final Map<AiRequestDispatchReceipt, AiPhysicalAttemptIdentity> identitiesByReceipt =
            new LinkedHashMap<>();
    private boolean closed;

    /** Creates an owner-thread state holder with the default bounded B0 coordinator limits. */
    public AiReviewOnlyPhysicalAttemptOwner(UUID ownerId) {
        this(ownerId, new AiPhysicalAttemptBudgetCoordinator());
    }

    /** Visible for deterministic pure-Java tests that inject a coordinator on its owner thread. */
    AiReviewOnlyPhysicalAttemptOwner(
            UUID ownerId, AiPhysicalAttemptBudgetCoordinator coordinator) {
        this.ownerId = requireNonZero(ownerId, "ownerId");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    /** Returns the real-player identity that owns every scope in this holder. */
    public UUID ownerId() {
        return ownerId;
    }

    /**
     * Reserves one canonical R1 physical attempt from server-owned fixed policy inputs.
     *
     * <p>The caller must have already rejected a non-canonical or stale dispatch. A scope is never
     * shared across a different owner, bot, or agent; all ledger mutations remain bounded and the
     * B0 coordinator captures the owner thread when this holder is constructed.
     */
    public AiPhysicalAttemptOfferResult offer(AiClientRequestDispatch dispatch) {
        requireOpen();
        AiClientRequestDispatch checked = Objects.requireNonNull(dispatch, "dispatch");
        if (!ownerId.equals(checked.ownerId())
                || !AiReviewOnlyContract.isCanonicalDispatch(checked)) {
            throw new IllegalArgumentException(
                    "physical attempt owner accepts only its canonical R1 dispatch");
        }
        AiTokenBudgetScope scope = new AiTokenBudgetScope(
                ownerId, checked.botId(), checked.agentId());
        AiTokenBudgetLedger ledger = ledgersByScope.computeIfAbsent(
                scope, ignored -> new AiTokenBudgetLedger(scope, LEDGER_POLICY));
        AiTokenBudgetRequestBinding binding = new AiTokenBudgetRequestBinding(
                scope, checked.requestId(), checked.revision());
        Instant deadline = Instant.ofEpochMilli(checked.expiresAtEpochMillis());
        AiRetryAttemptBudgetContext budgetContext = new AiRetryAttemptBudgetContext(
                ledger,
                binding,
                fixedAdmission(),
                deadline);
        AiPhysicalAttemptOfferResult result = coordinator.offer(
                new AiPhysicalAttemptOfferRequest(checked, budgetContext, deadline));
        result.offer().ifPresent(offer -> {
            AiRequestDispatchReceipt receipt = offer.identity().dispatchReceipt();
            AiPhysicalAttemptIdentity prior = identitiesByReceipt.putIfAbsent(
                    receipt, offer.identity());
            if (prior != null && !prior.equals(offer.identity())) {
                throw new IllegalStateException(
                        "physical attempt identity index is inconsistent");
            }
        });
        return result;
    }

    /** Delegates one lifecycle-authenticated exact ACK to the bounded B0 coordinator. */
    public AiPhysicalAttemptPrepareResult acknowledge(AiPhysicalAttemptPrepareAck prepareAck) {
        requireOpen();
        return coordinator.acknowledge(Objects.requireNonNull(prepareAck, "prepareAck"));
    }

    /** Finds only the exact active identity belonging to one safe dispatch receipt. */
    public Optional<AiPhysicalAttemptIdentity> findIdentity(
            AiRequestDispatchReceipt receipt) {
        return Optional.ofNullable(identitiesByReceipt.get(Objects.requireNonNull(
                receipt, "receipt")));
    }

    /**
     * Closes one known exact identity and drops its owner-side receipt index.
     *
     * <p>The underlying coordinator releases an unstarted reservation but only tombstones a grant;
     * this method never retries or broadens the close to another bot or request.
     */
    public Optional<AiPhysicalAttemptCloseResult> closeReceipt(
            AiRequestDispatchReceipt receipt) {
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        AiPhysicalAttemptIdentity identity = identitiesByReceipt.remove(checked);
        if (identity == null) {
            return Optional.empty();
        }
        return Optional.of(coordinator.closeExact(identity));
    }

    /** Expires bounded B0 entries; the lifecycle still owns ticket/gate expiry and cancellation. */
    public void expireDueAttempts() {
        requireOpen();
        coordinator.expireDueAttempts();
        coordinator.expireTombstones();
    }

    /** Number of exact active receipt indexes retained by this owner, for bounded diagnostics/tests. */
    public int indexedAttemptCount() {
        return identitiesByReceipt.size();
    }

    /**
     * Closes every physical attempt and ledger for owner logout or server shutdown.
     *
     * <p>Delegated B0 close semantics preserve committed accounting until this owner scope itself
     * is discarded; only unstarted reservations are released.
     */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        coordinator.closeAll();
        identitiesByReceipt.clear();
        ledgersByScope.values().forEach(AiTokenBudgetLedger::close);
        ledgersByScope.clear();
    }

    @Override
    public String toString() {
        return "AiReviewOnlyPhysicalAttemptOwner[bound=true, indexedAttempts="
                + identitiesByReceipt.size() + ", closed=" + closed + "]";
    }

    private static AiModelAdmission fixedAdmission() {
        long inputTokens = AiReviewOnlyContract.MAXIMUM_INPUT_TOKENS;
        long outputTokens = AiReviewOnlyContract.MAXIMUM_OUTPUT_TOKENS;
        return new AiModelAdmission(
                AiModelAdmissionStatus.ACCEPTED,
                inputTokens,
                outputTokens,
                Math.addExact(inputTokens, outputTokens));
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("physical attempt owner is closed");
        }
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID checked = Objects.requireNonNull(value, name);
        if (checked.getMostSignificantBits() == 0L
                && checked.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
        return checked;
    }
}
