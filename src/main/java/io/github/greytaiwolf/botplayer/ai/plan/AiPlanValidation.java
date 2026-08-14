package io.github.greytaiwolf.botplayer.ai.plan;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.Objects;
import java.util.UUID;

/**
 * Bounded result of converting a firewall-approved proposed tool batch into a P5-owned plan token.
 *
 * <p>Failure details stay within the P5 adapter. This DTO gives P6 only a stable result code and never
 * renders model tool arguments or the eventual plan's parameters.
 */
public record AiPlanValidation(
        AiPlanValidationStatus status,
        java.util.Optional<AiValidatedSkillPlan> validatedPlan) {
    public AiPlanValidation {
        status = Objects.requireNonNull(status, "status");
        validatedPlan = Objects.requireNonNull(validatedPlan, "validatedPlan");
        if ((status == AiPlanValidationStatus.ACCEPTED) != validatedPlan.isPresent()) {
            throw new IllegalArgumentException(
                    "only an accepted validation may carry a plan token");
        }
    }

    /** Creates an accepted result only if the opaque token is exactly bound to this proposal. */
    public static AiPlanValidation accepted(
            AiPlanBinding binding,
            ProposedSkillPlan proposal,
            AiValidatedSkillPlan validatedPlan) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(proposal, "proposal");
        AiValidatedSkillPlan token = Objects.requireNonNull(
                validatedPlan, "validatedPlan");
        UUID validationId = token.validationId();
        AiPlanChecks.requireNonZero(validationId, "validatedPlan.validationId");
        if (!binding.matches(proposal)
                || !binding.equals(token.binding())
                || token.sourceToolCallCount() != proposal.toolCalls().size()
                || token.sourceToolCallCount() < 1
                || token.nodeCount() < 1
                || token.nodeCount() > SkillPlan.ABSOLUTE_MAX_NODES
                || token.edgeCount() < 0
                || token.edgeCount() > SkillPlan.ABSOLUTE_MAX_EDGES) {
            throw new IllegalArgumentException(
                    "validated plan token is not a bounded match for its request");
        }
        return new AiPlanValidation(
                AiPlanValidationStatus.ACCEPTED,
                java.util.Optional.of(token));
    }

    public static AiPlanValidation rejected(AiPlanValidationStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == AiPlanValidationStatus.ACCEPTED) {
            throw new IllegalArgumentException("accepted validation needs a plan token");
        }
        return new AiPlanValidation(status, java.util.Optional.empty());
    }

    @Override
    public String toString() {
        return "AiPlanValidation[status=" + status
                + ", validated=" + validatedPlan.isPresent() + "]";
    }
}
