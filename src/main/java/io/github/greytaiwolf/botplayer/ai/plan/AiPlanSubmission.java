package io.github.greytaiwolf.botplayer.ai.plan;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Non-secret result of asking the P5 port to submit its own validated plan token. */
public record AiPlanSubmission(
        AiPlanSubmissionStatus status, Optional<UUID> skillRunId) {
    public AiPlanSubmission {
        status = Objects.requireNonNull(status, "status");
        skillRunId = Objects.requireNonNull(skillRunId, "skillRunId");
        if ((status == AiPlanSubmissionStatus.SUBMITTED) != skillRunId.isPresent()) {
            throw new IllegalArgumentException(
                    "only a submitted result may carry a skill run id");
        }
        skillRunId.ifPresent(id -> AiPlanChecks.requireNonZero(id, "skillRunId"));
    }

    public static AiPlanSubmission submitted(UUID skillRunId) {
        return new AiPlanSubmission(
                AiPlanSubmissionStatus.SUBMITTED,
                Optional.of(skillRunId));
    }

    public static AiPlanSubmission rejected(AiPlanSubmissionStatus status) {
        Objects.requireNonNull(status, "status");
        if (status == AiPlanSubmissionStatus.SUBMITTED) {
            throw new IllegalArgumentException("submitted result needs a skill run id");
        }
        return new AiPlanSubmission(status, Optional.empty());
    }

    @Override
    public String toString() {
        return "AiPlanSubmission[status=" + status
                + ", submitted=" + skillRunId.isPresent() + "]";
    }
}
