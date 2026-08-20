package io.github.greytaiwolf.botplayer.ai.transport;

import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiRequestSchedulerPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.network.payload.AiProposalPayload;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;

/**
 * Server-thread owner of the generic client-sponsored request session state.
 *
 * <p>This coordinator composes the proposal gate and immutable binding ledger into one exact
 * lifecycle boundary. It enforces a global active-request cap, captures one owner thread, and
 * exposes a bounded non-blocking terminal mailbox for a future client or Scheduler lane. It does
 * not send a packet, read credentials, construct a Provider, submit or cancel an
 * {@code AiRequestScheduler} request, invoke P5, or access Minecraft/world objects.
 * {@link #reviewProposal(AiProposalPayload, AiProposalAuthority, long)} only reviews an already-
 * decoded untrusted DTO against its immutable gate/ledger session; it is not a packet handler and
 * may return only the gate's still-unexecuted review DTO. Terminal mailbox observations are
 * intentionally only observed; the owner must later choose and perform an explicit exact close.
 */
public final class AiClientSponsoredRequestCoordinator {
    /** Hard ceiling so an embedding cannot turn the terminal mailbox into an unbounded queue. */
    public static final int MAX_TERMINAL_MAILBOX_CAPACITY = 1_024;
    /** Hard ceiling for one owner-thread drain batch. */
    public static final int MAX_TERMINAL_OBSERVATIONS_PER_DRAIN = 128;

    private final Thread ownerThread;
    private final AiProposalSessionGate gate;
    private final AiClientSponsoredRequestLedger ledger;
    private final Limits limits;
    private final ArrayBlockingQueue<AiClientSponsoredTerminalObservation> terminalMailbox;

    public AiClientSponsoredRequestCoordinator() {
        this(Limits.defaults());
    }

    public AiClientSponsoredRequestCoordinator(Limits limits) {
        this(new AiProposalSessionGate(), new AiClientSponsoredRequestLedger(), limits);
    }

    /** Package-private injection point for deterministic pure-Java tests. */
    AiClientSponsoredRequestCoordinator(
            AiProposalSessionGate gate,
            AiClientSponsoredRequestLedger ledger,
            Limits limits) {
        ownerThread = Thread.currentThread();
        this.gate = Objects.requireNonNull(gate, "gate");
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.limits = Objects.requireNonNull(limits, "limits");
        terminalMailbox = new ArrayBlockingQueue<>(limits.terminalMailboxCapacity());
    }

    /**
     * Opens one new client-sponsored request without ever implicitly replacing the same Bot.
     *
     * <p>All request-template and epoch-deadline validation runs before the gate mutation. A
     * capacity or same-Bot rejection is therefore non-destructive and carries no binding.
     */
    public AiClientSponsoredRequestOpenResult open(OpenRequest openRequest) {
        requireOwnerThread();
        OpenRequest request = validateOpenRequest(openRequest);
        requireActiveCountsMatch();
        if (ledger.hasActiveRequestForBot(request.botId())) {
            return AiClientSponsoredRequestOpenResult.rejected(
                    AiClientSponsoredRequestOpenStatus.BOT_BUSY);
        }
        if (ledger.activeRequestCount() >= limits.maximumActiveRequests()) {
            return AiClientSponsoredRequestOpenResult.rejected(
                    AiClientSponsoredRequestOpenStatus.CAPACITY_EXHAUSTED);
        }

        AiProposalRequestEnvelope envelope = gate.open(
                request.botId(),
                request.ownerId(),
                request.agentId(),
                request.generation(),
                request.revision(),
                request.currentTick(),
                request.ttlTicks(),
                request.purpose(),
                request.toolCallsAllowed(),
                request.firewallPolicy());
        AiClientSponsoredRequest binding = null;
        boolean ledgerOpened = false;
        try {
            binding = bind(request, envelope);
            ledger.open(binding);
            ledgerOpened = true;
            requireActiveCountsMatch();
            return AiClientSponsoredRequestOpenResult.opened(binding);
        } catch (RuntimeException exception) {
            failClosedAfterOpen(envelope, binding, ledgerOpened, exception);
            throw exception;
        }
    }

    /**
     * Explicitly replaces the active request for the same Bot and returns its exact old binding.
     *
     * <p>When the global cap is full, this operation remains admissible only if this coordinator
     * already owns an active binding for the same Bot; replacement does not increase the count.
     */
    public AiClientSponsoredRequestOpenResult openReplacing(OpenRequest openRequest) {
        requireOwnerThread();
        OpenRequest request = validateOpenRequest(openRequest);
        requireActiveCountsMatch();
        boolean sameBotAlreadyActive = ledger.hasActiveRequestForBot(request.botId());
        if (!sameBotAlreadyActive
                && ledger.activeRequestCount() >= limits.maximumActiveRequests()) {
            return AiClientSponsoredRequestOpenResult.rejected(
                    AiClientSponsoredRequestOpenStatus.CAPACITY_EXHAUSTED);
        }

        AiProposalOpenResult gateResult = gate.openReplacing(
                request.botId(),
                request.ownerId(),
                request.agentId(),
                request.generation(),
                request.revision(),
                request.currentTick(),
                request.ttlTicks(),
                request.purpose(),
                request.toolCallsAllowed(),
                request.firewallPolicy());
        try {
            AiClientSponsoredRequest binding = bind(request, gateResult.opened());
            Optional<AiClientSponsoredRequest> displaced = ledger.replace(
                    binding,
                    gateResult.replaced().map(AiRequestDispatchReceipt::fromEnvelope));
            requireReplacementMatches(gateResult, displaced);
            requireActiveCountsMatch();
            return AiClientSponsoredRequestOpenResult.opened(binding, displaced);
        } catch (RuntimeException exception) {
            failClosedAfterReplacement(gateResult, exception);
            throw exception;
        }
    }

    /**
     * Closes only the request represented by the full immutable receipt.
     *
     * <p>A stale receipt returns empty only when both owned structures no longer contain it. Any
     * gate/ledger disagreement is fail-closed and reported as an invariant failure after both
     * exact sides have been removed where possible.
     */
    public Optional<AiClientSponsoredRequest> closeExact(AiRequestDispatchReceipt receipt) {
        requireOwnerThread();
        AiRequestDispatchReceipt checked = Objects.requireNonNull(receipt, "receipt");
        Optional<AiProposalRequestEnvelope> gateClosed = gate.closeExact(checked);
        Optional<AiClientSponsoredRequest> ledgerClosed = ledger.closeExact(checked);
        requireExactClosureMatches("closeExact", checked, gateClosed, ledgerClosed);
        return ledgerClosed;
    }

    /** Closes one retiring Bot's exact active binding from both owned structures. */
    public Optional<AiClientSponsoredRequest> closeBot(UUID botId) {
        requireOwnerThread();
        UUID checked = requireNonZero(botId, "botId");
        Optional<AiProposalRequestEnvelope> gateClosed = gate.closeBot(checked);
        Optional<AiClientSponsoredRequest> ledgerClosed = ledger.closeBot(checked);
        requireClosuresMatch("closeBot", gateClosed, ledgerClosed);
        return ledgerClosed;
    }

    /** Closes the half-open tick TTLs that no longer contain {@code currentTick}. */
    public List<AiClientSponsoredRequest> closeExpiredThrough(long currentTick) {
        requireOwnerThread();
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        List<AiProposalRequestEnvelope> gateClosed = gate.closeExpiredThrough(currentTick);
        List<AiClientSponsoredRequest> ledgerClosed = ledger.closeExpiredThrough(currentTick);
        requireClosureSetsMatch("closeExpiredThrough", gateClosed, ledgerClosed);
        return List.copyOf(ledgerClosed);
    }

    /** Closes all active bindings, without discarding already-reported terminal observations. */
    public List<AiClientSponsoredRequest> closeAll() {
        requireOwnerThread();
        List<AiProposalRequestEnvelope> gateClosed = gate.closeAll();
        List<AiClientSponsoredRequest> ledgerClosed = ledger.closeAll();
        requireClosureSetsMatch("closeAll", gateClosed, ledgerClosed);
        return List.copyOf(ledgerClosed);
    }

    /** Returns the coherent active binding count only from the owner thread. */
    public int activeRequestCount() {
        requireOwnerThread();
        requireActiveCountsMatch();
        return ledger.activeRequestCount();
    }

    /**
     * Reviews one generic client-sponsored proposal through one owner-thread exact transaction.
     *
     * <p>The immutable ledger correlation is checked before the gate sees the payload. A stale or
     * partial C2S tuple therefore cannot consume a nonce, learn gate-specific status, or close a
     * newer replacement. Once the full tuple matches, the gate remains authoritative for dynamic
     * owner/agent/generation/TTL, codec and Firewall checks. A terminal gate receipt must then
     * close the same ledger binding before this method returns it; no network, Scheduler, plan, or
     * world side effect is performed here.
     */
    public AiClientSponsoredProposalReviewResult reviewProposal(
            AiProposalPayload payload, AiProposalAuthority authority,
            long currentTick) {
        requireOwnerThread();
        AiProposalPayload checkedPayload = Objects.requireNonNull(payload,
                "payload");
        AiProposalAuthority checkedAuthority = Objects.requireNonNull(authority,
                "authority");
        if (currentTick < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        requireActiveCountsMatch();
        Optional<AiClientSponsoredRequest> prechecked = ledger.findMatching(
                checkedPayload);
        if (prechecked.isEmpty()) {
            return AiClientSponsoredProposalReviewResult.droppedBeforeGate();
        }

        AiClientSponsoredRequest expected = prechecked.orElseThrow();
        AiProposalReviewReceipt receipt = gate.reviewWithReceipt(checkedPayload,
                checkedAuthority, currentTick);
        if (receipt.terminalDispatch().isEmpty()) {
            if (isImpossibleNonTerminalAfterExactPrecheck(
                    receipt.review().status(), checkedAuthority)) {
                failClosedReviewLedger(expected,
                        "client-sponsored gate disagreed with an exact ledger precheck");
            }
            requireActiveCountsMatch();
            return AiClientSponsoredProposalReviewResult.nonTerminal(
                    receipt.review());
        }

        AiRequestDispatchReceipt terminalReceipt = receipt.terminalDispatch()
                .orElseThrow();
        if (!expected.matches(terminalReceipt)) {
            failClosedReviewLedger(expected,
                    "client-sponsored gate terminal receipt did not match its ledger precheck");
        }
        Optional<AiClientSponsoredRequest> closed = ledger.closeExact(
                terminalReceipt);
        if (closed.isEmpty() || closed.orElseThrow() != expected) {
            failClosedReviewLedger(expected,
                    "client-sponsored ledger could not exact-close a gate terminal receipt");
        }
        requireActiveCountsMatch();
        return AiClientSponsoredProposalReviewResult.terminal(receipt.review(),
                closed.orElseThrow());
    }

    /**
     * Offers a safe terminal observation from any thread without blocking or mutating session
     * ownership. A full inbox returns {@code false}; callers must not spin on the server Tick.
     */
    public boolean offerTerminalObservation(
            AiClientSponsoredTerminalObservation observation) {
        return terminalMailbox.offer(Objects.requireNonNull(observation, "observation"));
    }

    /** Drains no more than the configured bounded batch, preserving FIFO order. */
    public List<AiClientSponsoredTerminalObservation> drainTerminalObservations() {
        return drainTerminalObservations(limits.maximumTerminalObservationsPerDrain());
    }

    /**
     * Drains a caller-selected bounded prefix of the terminal inbox.
     *
     * <p>Draining never checks whether the receipt is current and never closes gate or ledger
     * state. That policy belongs to a later lifecycle integration on the owner thread.
     */
    public List<AiClientSponsoredTerminalObservation> drainTerminalObservations(int maximum) {
        requireOwnerThread();
        if (maximum < 1 || maximum > limits.maximumTerminalObservationsPerDrain()) {
            throw new IllegalArgumentException(
                    "maximum must be between 1 and "
                            + limits.maximumTerminalObservationsPerDrain());
        }
        List<AiClientSponsoredTerminalObservation> drained = new ArrayList<>(maximum);
        for (int index = 0; index < maximum; index++) {
            AiClientSponsoredTerminalObservation observation = terminalMailbox.poll();
            if (observation == null) {
                break;
            }
            drained.add(observation);
        }
        return List.copyOf(drained);
    }

    /** Owner-thread diagnostic count; it exposes no receipt, prompt, nonce or client content. */
    public int terminalMailboxSize() {
        requireOwnerThread();
        return terminalMailbox.size();
    }

    /** Bound configuration for this pure session owner. */
    public record Limits(
            int maximumActiveRequests,
            int terminalMailboxCapacity,
            int maximumTerminalObservationsPerDrain) {
        public Limits {
            if (maximumActiveRequests < 1
                    || maximumActiveRequests > AiRequestSchedulerPolicy.MAX_GLOBAL_IN_FLIGHT) {
                throw new IllegalArgumentException(
                        "maximumActiveRequests must be between 1 and "
                                + AiRequestSchedulerPolicy.MAX_GLOBAL_IN_FLIGHT);
            }
            if (terminalMailboxCapacity < 1
                    || terminalMailboxCapacity > MAX_TERMINAL_MAILBOX_CAPACITY) {
                throw new IllegalArgumentException(
                        "terminalMailboxCapacity must be between 1 and "
                                + MAX_TERMINAL_MAILBOX_CAPACITY);
            }
            if (maximumTerminalObservationsPerDrain < 1
                    || maximumTerminalObservationsPerDrain > terminalMailboxCapacity
                    || maximumTerminalObservationsPerDrain
                    > MAX_TERMINAL_OBSERVATIONS_PER_DRAIN) {
                throw new IllegalArgumentException(
                        "maximumTerminalObservationsPerDrain must be between 1 and min("
                                + "terminalMailboxCapacity, "
                                + MAX_TERMINAL_OBSERVATIONS_PER_DRAIN + ")");
            }
        }

        public static Limits defaults() {
            return new Limits(4, 256, 16);
        }
    }

    /**
     * Immutable, owner-thread opening input. Its diagnostic intentionally omits owner and all
     * request-template contents, which may contain user text.
     */
    public record OpenRequest(
            UUID serverInstanceId,
            UUID botId,
            UUID ownerId,
            UUID agentId,
            long generation,
            long revision,
            long currentTick,
            int ttlTicks,
            AiRequestPurpose purpose,
            boolean toolCallsAllowed,
            ToolFirewallPolicy firewallPolicy,
            long issuedAtEpochMillis,
            long expiresAtEpochMillis,
            String providerId,
            AiRequest requestTemplate) {
        @Override
        public String toString() {
            return "AiClientSponsoredRequestCoordinator.OpenRequest[botId=" + botId
                    + ", agentId=" + agentId
                    + ", generation=" + generation
                    + ", revision=" + revision
                    + ", currentTick=" + currentTick
                    + ", ttlTicks=" + ttlTicks
                    + ", purpose=" + purpose
                    + ", toolCallsAllowed=" + toolCallsAllowed
                    + ", issuedAtEpochMillis=" + issuedAtEpochMillis
                    + ", expiresAtEpochMillis=" + expiresAtEpochMillis
                    + ", providerId=" + providerId + "]";
        }
    }

    /** This coordinator's diagnostic intentionally does not expose owner, binding or mailbox data. */
    @Override
    public String toString() {
        return "AiClientSponsoredRequestCoordinator[ownerThreadBound=true]";
    }

    private OpenRequest validateOpenRequest(OpenRequest openRequest) {
        OpenRequest request = Objects.requireNonNull(openRequest, "openRequest");
        requireNonZero(request.serverInstanceId(), "serverInstanceId");
        requireNonZero(request.botId(), "botId");
        requireNonZero(request.ownerId(), "ownerId");
        requireNonZero(request.agentId(), "agentId");
        if (request.generation() <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        if (request.revision() <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        if (request.currentTick() < 0L) {
            throw new IllegalArgumentException("currentTick must not be negative");
        }
        if (request.ttlTicks() < 1
                || request.ttlTicks() > AiProposalSessionGate.MAX_REQUEST_TTL_TICKS) {
            throw new IllegalArgumentException(
                    "ttlTicks must be between 1 and "
                            + AiProposalSessionGate.MAX_REQUEST_TTL_TICKS);
        }
        try {
            Math.addExact(request.currentTick(), request.ttlTicks());
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("request expiry overflow", exception);
        }
        Objects.requireNonNull(request.purpose(), "purpose");
        Objects.requireNonNull(request.firewallPolicy(), "firewallPolicy");
        AiClientRequestDispatch.requireDispatchableTemplate(
                request.purpose(),
                request.providerId(),
                request.requestTemplate(),
                request.ttlTicks(),
                request.issuedAtEpochMillis(),
                request.expiresAtEpochMillis());
        return request;
    }

    private AiClientSponsoredRequest bind(
            OpenRequest request, AiProposalRequestEnvelope envelope) {
        return AiClientSponsoredRequest.bind(
                request.serverInstanceId(),
                envelope,
                request.issuedAtEpochMillis(),
                request.expiresAtEpochMillis(),
                request.providerId(),
                request.requestTemplate());
    }

    private void failClosedAfterOpen(
            AiProposalRequestEnvelope envelope,
            AiClientSponsoredRequest binding,
            boolean ledgerOpened,
            RuntimeException failure) {
        closeGateExactOrSuppress(envelope, failure);
        if (ledgerOpened && binding != null) {
            try {
                if (ledger.closeExact(binding.dispatchReceipt()).isEmpty()) {
                    failure.addSuppressed(new IllegalStateException(
                            "coordinator ledger cleanup could not find its opened binding"));
                }
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
        }
    }

    private void failClosedAfterReplacement(
            AiProposalOpenResult gateResult,
            RuntimeException failure) {
        AiProposalOpenResult checked = Objects.requireNonNull(gateResult, "gateResult");
        closeGateExactOrSuppress(checked.opened(), failure);
        /*
         * Do not rediscover by bot id here. The original ledger binding may still be present when
         * bind/replace failed, while the replacement may already be present when a later invariant
         * check failed. These are the only two exact receipts this replacement transaction owns.
         */
        closeLedgerExactOrSuppress(
                AiRequestDispatchReceipt.fromEnvelope(checked.opened()), failure);
        checked.replaced().map(AiRequestDispatchReceipt::fromEnvelope).ifPresent(
                receipt -> closeLedgerExactOrSuppress(receipt, failure));
    }

    private void closeGateExactOrSuppress(
            AiProposalRequestEnvelope envelope, RuntimeException failure) {
        try {
            if (gate.closeExact(AiRequestDispatchReceipt.fromEnvelope(envelope)).isEmpty()) {
                failure.addSuppressed(new IllegalStateException(
                        "coordinator gate cleanup could not find its opened envelope"));
            }
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void closeLedgerExactOrSuppress(
            AiRequestDispatchReceipt receipt, RuntimeException failure) {
        try {
            /* Empty is valid for the transaction phase in which this binding was never recorded. */
            ledger.closeExact(receipt);
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private static void requireReplacementMatches(
            AiProposalOpenResult gateResult,
            Optional<AiClientSponsoredRequest> ledgerReplaced) {
        Optional<AiProposalRequestEnvelope> gateReplaced = gateResult.replaced();
        if (gateReplaced.isPresent() != ledgerReplaced.isPresent()) {
            throw new IllegalStateException(
                    "client-sponsored replacement gate and ledger disagree on the old binding");
        }
        if (gateReplaced.isPresent()
                && !AiRequestDispatchReceipt.fromEnvelope(gateReplaced.orElseThrow()).equals(
                ledgerReplaced.orElseThrow().dispatchReceipt())) {
            throw new IllegalStateException(
                    "client-sponsored replacement gate and ledger displaced different bindings");
        }
    }

    private void requireActiveCountsMatch() {
        if (gate.activeRequestCount() != ledger.activeRequestCount()) {
            throw new IllegalStateException(
                    "client-sponsored gate and ledger active counts are inconsistent");
        }
    }

    private void failClosedReviewLedger(AiClientSponsoredRequest expected,
            String message) {
        IllegalStateException failure = new IllegalStateException(message);
        try {
            Optional<AiClientSponsoredRequest> closed = ledger.closeExact(
                    expected.dispatchReceipt());
            if (closed.isEmpty()) {
                failure.addSuppressed(new IllegalStateException(
                        "client-sponsored review cleanup could not find its prechecked binding"));
            }
        } catch (RuntimeException cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
        throw failure;
    }

    private static boolean isImpossibleNonTerminalAfterExactPrecheck(
            AiProposalReviewStatus status, AiProposalAuthority authority) {
        return switch (Objects.requireNonNull(status, "status")) {
            case NO_ACTIVE_REQUEST, BOT_MISMATCH, AGENT_MISMATCH,
                    NONCE_MISMATCH, REVISION_MISMATCH, EXPIRED,
                    REVIEW_CONTRACT_REJECTED, TOOL_CALLS_NOT_ALLOWED,
                    NO_TOOL_CALLS, MALFORMED_TOOL_CALL, TOOL_REJECTED,
                    ACCEPTED_NO_EXECUTION -> true;
            case BOT_NOT_ACTIVE, NOT_OWNER, OWNER_CHANGED, AGENT_NOT_BOUND -> false;
            case GENERATION_MISMATCH -> Objects.requireNonNull(
                    authority, "authority").activeGeneration().isPresent();
        };
    }

    private static void requireExactClosureMatches(
            String operation,
            AiRequestDispatchReceipt expected,
            Optional<AiProposalRequestEnvelope> gateClosed,
            Optional<AiClientSponsoredRequest> ledgerClosed) {
        if (gateClosed.isEmpty() && ledgerClosed.isEmpty()) {
            return;
        }
        if (gateClosed.isEmpty() || ledgerClosed.isEmpty()
                || !AiRequestDispatchReceipt.fromEnvelope(gateClosed.orElseThrow()).equals(expected)
                || !ledgerClosed.orElseThrow().matches(expected)) {
            throw new IllegalStateException(
                    "client-sponsored gate and ledger diverged during " + operation);
        }
    }

    private static void requireClosuresMatch(
            String operation,
            Optional<AiProposalRequestEnvelope> gateClosed,
            Optional<AiClientSponsoredRequest> ledgerClosed) {
        if (gateClosed.isEmpty() && ledgerClosed.isEmpty()) {
            return;
        }
        if (gateClosed.isEmpty() || ledgerClosed.isEmpty()
                || !AiRequestDispatchReceipt.fromEnvelope(gateClosed.orElseThrow()).equals(
                ledgerClosed.orElseThrow().dispatchReceipt())) {
            throw new IllegalStateException(
                    "client-sponsored gate and ledger diverged during " + operation);
        }
    }

    private static void requireClosureSetsMatch(
            String operation,
            List<AiProposalRequestEnvelope> gateClosed,
            List<AiClientSponsoredRequest> ledgerClosed) {
        Set<AiRequestDispatchReceipt> gateReceipts = new HashSet<>();
        gateClosed.forEach(envelope -> gateReceipts.add(
                AiRequestDispatchReceipt.fromEnvelope(envelope)));
        Set<AiRequestDispatchReceipt> ledgerReceipts = new HashSet<>();
        ledgerClosed.forEach(binding -> ledgerReceipts.add(binding.dispatchReceipt()));
        if (gateReceipts.size() != gateClosed.size()
                || ledgerReceipts.size() != ledgerClosed.size()
                || !gateReceipts.equals(ledgerReceipts)) {
            throw new IllegalStateException(
                    "client-sponsored gate and ledger diverged during " + operation);
        }
    }

    private void requireOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException(
                    "client-sponsored request coordinator must run on its owner thread");
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
