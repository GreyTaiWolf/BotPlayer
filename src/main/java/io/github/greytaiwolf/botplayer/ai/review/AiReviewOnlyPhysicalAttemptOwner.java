package io.github.greytaiwolf.botplayer.ai.review;

import io.github.greytaiwolf.botplayer.ai.AiModelAdmission;
import io.github.greytaiwolf.botplayer.ai.AiModelAdmissionStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptBudgetCoordinator;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptCloseResult;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptCloseStatus;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptIdentity;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOfferRequest;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptOfferResult;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareAck;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptPrepareResult;
import io.github.greytaiwolf.botplayer.ai.AiPhysicalAttemptStartGrant;
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
import java.util.List;
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

    private final Thread ownerThread;
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
        ownerThread = Thread.currentThread();
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
        requireOwnerThread();
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
        requireOwnerThread();
        requireOpen();
        return coordinator.acknowledge(Objects.requireNonNull(prepareAck, "prepareAck"));
    }

    /**
     * Returns an already-settled grant only when the owner index and B0 coordinator still agree
     * on the complete physical-attempt identity.
     *
     * <p>The query is intentionally non-settling. It cannot settle an offer or keep a grant alive
     * after its exact lifecycle ticket has been removed or its physical-start deadline has
     * elapsed.
     */
    public Optional<AiPhysicalAttemptStartGrant> findGrantedExact(
            AiPhysicalAttemptIdentity identity) {
        requireOwnerThread();
        requireOpen();
        AiPhysicalAttemptIdentity checked = Objects.requireNonNull(identity, "identity");
        if (!checked.equals(identitiesByReceipt.get(checked.dispatchReceipt()))) {
            return Optional.empty();
        }
        return coordinator.findGrantedExact(checked);
    }

    /** Finds only the exact active identity belonging to one safe dispatch receipt. */
    public Optional<AiPhysicalAttemptIdentity> findIdentity(
            AiRequestDispatchReceipt receipt) {
        requireOwnerThread();
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
        requireOwnerThread();
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        AiPhysicalAttemptIdentity identity = identitiesByReceipt.get(checked);
        if (identity == null) {
            return Optional.empty();
        }
        return Optional.of(closeIndexedIdentity(checked, identity));
    }

    /**
     * Closes only the stored identity that is byte-for-byte equal to the lifecycle ticket.
     *
     * <p>A receipt alone is enough only for owner-local cleanup. A production lifecycle should
     * prefer this method so a stale or internally divergent ticket cannot close a later attempt
     * that happens to share a request receipt.
     */
    public Optional<AiPhysicalAttemptCloseResult> closeExact(
            AiPhysicalAttemptIdentity identity) {
        requireOwnerThread();
        AiPhysicalAttemptIdentity checked = Objects.requireNonNull(identity, "identity");
        AiPhysicalAttemptIdentity current = identitiesByReceipt.get(
                checked.dispatchReceipt());
        if (!checked.equals(current)) {
            return Optional.empty();
        }
        return Optional.of(closeIndexedIdentity(checked.dispatchReceipt(), checked));
    }

    /** Compatibility expiry API for callers that do not own lifecycle correlation. */
    public void expireDueAttempts() {
        expireDueAttemptIdentities();
    }

    /**
     * Expires bounded B0 entries and returns their exact identities for lifecycle ticket/gate
     * cleanup.
     */
    public List<AiPhysicalAttemptIdentity> expireDueAttemptIdentities() {
        requireOwnerThread();
        requireOpen();
        List<AiPhysicalAttemptIdentity> expired = coordinator
                .expireDueAttemptIdentities().identities();
        expired.forEach(identity -> identitiesByReceipt.remove(
                identity.dispatchReceipt(), identity));
        coordinator.expireTombstones();
        return expired;
    }

    /** Number of exact active receipt indexes retained by this owner, for bounded diagnostics/tests. */
    public int indexedAttemptCount() {
        requireOwnerThread();
        return identitiesByReceipt.size();
    }

    /**
     * Drops one ended owner/bot/agent ledger scope after every exact attempt for that scope closed.
     *
     * <p>This is deliberately not a request-terminal operation: the active binding keeps its
     * committed R1 budget until lifecycle unbind, retirement, logout, or shutdown. Returning
     * {@code false} means either there was no retained ledger or an exact attempt still protects
     * it, so the caller must not pretend the binding cleanup completed.
     */
    public boolean closeScope(UUID botId, UUID agentId) {
        requireOwnerThread();
        requireOpen();
        AiTokenBudgetScope scope = new AiTokenBudgetScope(
                ownerId,
                requireNonZero(botId, "botId"),
                requireNonZero(agentId, "agentId"));
        boolean activeAttemptRemains = identitiesByReceipt.values().stream()
                .map(AiPhysicalAttemptIdentity::dispatchReceipt)
                .anyMatch(receipt -> receipt.botId().equals(scope.botId())
                        && receipt.agentId().equals(scope.agentId()));
        if (activeAttemptRemains) {
            return false;
        }
        AiTokenBudgetLedger ledger = ledgersByScope.remove(scope);
        if (ledger == null) {
            return false;
        }
        ledger.close();
        return true;
    }

    /**
     * Closes every physical attempt and ledger for owner logout or server shutdown.
     *
     * <p>Delegated B0 close semantics preserve committed accounting until this owner scope itself
     * is discarded; only unstarted reservations are released.
     */
    @Override
    public void close() {
        requireOwnerThread();
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

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "physical attempt owner must run on its construction thread");
        }
    }

    /**
     * Lets the coordinator enforce its owner thread before this owner mutates its local index.
     * A coordinator identity mismatch remains indexed for diagnosis rather than hiding a divergent
     * live attempt.
     */
    private AiPhysicalAttemptCloseResult closeIndexedIdentity(
            AiRequestDispatchReceipt receipt, AiPhysicalAttemptIdentity identity) {
        AiPhysicalAttemptCloseResult result = coordinator.closeExact(identity);
        if (result.status() != AiPhysicalAttemptCloseStatus.IDENTITY_MISMATCH) {
            identitiesByReceipt.remove(receipt, identity);
        }
        return result;
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
