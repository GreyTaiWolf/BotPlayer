package io.github.greytaiwolf.botplayer.ai;

import io.github.greytaiwolf.botplayer.ai.transport.AiClientRequestDispatch;
import java.time.Instant;
import java.util.Objects;

/**
 * Trusted server-side input for offering one distributed physical attempt.
 *
 * <p>The future lifecycle/session bridge must obtain {@link #dispatch()} from its exact active
 * client-sponsored binding and {@link #budgetContext()} from trusted admission. The coordinator,
 * rather than this caller input, chooses the opaque attempt id. This pure DTO cannot make the
 * lifecycle decision itself, does not send an offer, and authorizes no Provider, Scheduler, Skill,
 * Action, or Minecraft operation.
 */
public record AiPhysicalAttemptOfferRequest(
        AiClientRequestDispatch dispatch,
        AiRetryAttemptBudgetContext budgetContext,
        Instant attemptDeadline) {
    public AiPhysicalAttemptOfferRequest {
        dispatch = Objects.requireNonNull(dispatch, "dispatch");
        budgetContext = Objects.requireNonNull(budgetContext, "budgetContext");
        attemptDeadline = AiChecks.instant(attemptDeadline, "attemptDeadline");
    }

    /** Does not render prompt, schema, nonce, budget scope, or request identity. */
    @Override
    public String toString() {
        return "AiPhysicalAttemptOfferRequest[bound=true, attemptDeadline="
                + attemptDeadline + "]";
    }
}
