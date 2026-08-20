package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;

/**
 * Trusted, immutable budget input for all physical retry attempts of one logical request.
 *
 * <p>A future bridge must derive the binding and upstream deadline from its authoritative
 * owner/bot/agent/session request. A retry wrapper then supplies its own logical attempt deadline
 * to {@link #reservePhysicalAttempt(Instant)}; the ledger computes the earlier of both deadlines
 * and its bounded TTL. Every call reserves a fresh token for one possible physical Provider
 * invocation. The returned reservation must be released if cancellation wins before settle, or
 * settled immediately before exactly one remote call; this context never authorizes a Provider,
 * client session, Skill, Action, or Minecraft operation.
 */
public record AiRetryAttemptBudgetContext(
        AiTokenBudgetLedger ledger,
        AiTokenBudgetRequestBinding binding,
        AiModelAdmission admission,
        Instant upstreamDeadline) {
    public AiRetryAttemptBudgetContext {
        ledger = Objects.requireNonNull(ledger, "ledger");
        binding = Objects.requireNonNull(binding, "binding");
        admission = Objects.requireNonNull(admission, "admission");
        upstreamDeadline = AiChecks.instant(upstreamDeadline, "upstreamDeadline");
    }

    /**
     * Creates a new reservation for one physical attempt, bounded by both trusted deadlines.
     *
     * <p>The operation intentionally delegates admission/scope/closed/clock failures to the
     * ledger as a stable result rather than inventing a second budget state machine.
     */
    public AiTokenBudgetReservationResult reservePhysicalAttempt(Instant attemptDeadline) {
        Instant checkedAttemptDeadline = AiChecks.instant(
                attemptDeadline, "attemptDeadline");
        Instant effectiveDeadline = checkedAttemptDeadline.isBefore(upstreamDeadline)
                ? checkedAttemptDeadline
                : upstreamDeadline;
        return ledger.reserveForDeadline(binding, admission, effectiveDeadline);
    }

    /** Request and ownership identifiers are deliberately omitted from ordinary diagnostics. */
    @Override
    public String toString() {
        return "AiRetryAttemptBudgetContext[bound=true, admissionStatus="
                + admission.status() + "]";
    }
}
