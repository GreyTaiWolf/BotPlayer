package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import java.util.Objects;
import java.util.Optional;

/** 判定结果与可能提交的新 generation 请求。 */
public record SkillCheckpointRecoveryCoordination(
        SkillCheckpointRecoveryCoordinationStatus status,
        SkillCheckpointRecoveryDisposition disposition,
        Optional<SkillCheckpointRecoveryRejection> rejection,
        Optional<SkillRunRequest> request,
        Optional<SkillCheckpointRestartPlan> restartPlan) {
    public SkillCheckpointRecoveryCoordination {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(disposition, "disposition");
        rejection = Objects.requireNonNull(rejection, "rejection");
        request = Objects.requireNonNull(request, "request");
        restartPlan = Objects.requireNonNull(restartPlan, "restartPlan");
        if (status == SkillCheckpointRecoveryCoordinationStatus.READY
                && (disposition
                                != SkillCheckpointRecoveryDisposition
                                        .RESTART_FROM_SAFE_PHASE
                        || rejection.isPresent()
                        || request.isEmpty()
                        || restartPlan.isEmpty())) {
            throw new IllegalArgumentException(
                    "ready coordination requires a restart decision, request and lineage");
        }
        if (status == SkillCheckpointRecoveryCoordinationStatus.DECISION_REJECTED
                && (disposition
                                != SkillCheckpointRecoveryDisposition.REJECT
                        || rejection.isEmpty()
                        || request.isPresent()
                        || restartPlan.isPresent())) {
            throw new IllegalArgumentException(
                    "decision rejection must not expose a request");
        }
        if ((status
                                == SkillCheckpointRecoveryCoordinationStatus
                                        .RESTART_PLAN_UNAVAILABLE
                        || status
                                == SkillCheckpointRecoveryCoordinationStatus
                                        .RESTART_PLAN_REJECTED)
                && (disposition
                                != SkillCheckpointRecoveryDisposition
                                        .RESTART_FROM_SAFE_PHASE
                        || rejection.isPresent()
                        || request.isPresent()
                        || restartPlan.isPresent())) {
            throw new IllegalArgumentException(
                    "restart-plan failure requires an accepted decision without a request");
        }
    }

    static SkillCheckpointRecoveryCoordination ready(
            SkillCheckpointRecoveryOutcome decision,
            SkillRunRequest request,
            SkillCheckpointRestartPlan restartPlan) {
        return new SkillCheckpointRecoveryCoordination(
                SkillCheckpointRecoveryCoordinationStatus.READY,
                decision.disposition(),
                Optional.empty(),
                Optional.of(Objects.requireNonNull(request, "request")),
                Optional.of(Objects.requireNonNull(restartPlan, "restartPlan")));
    }

    static SkillCheckpointRecoveryCoordination decisionRejected(
            SkillCheckpointRecoveryOutcome decision) {
        return new SkillCheckpointRecoveryCoordination(
                SkillCheckpointRecoveryCoordinationStatus.DECISION_REJECTED,
                decision.disposition(),
                decision.rejection(),
                Optional.empty(),
                Optional.empty());
    }

    static SkillCheckpointRecoveryCoordination restartPlanUnavailable(
            SkillCheckpointRecoveryOutcome decision) {
        return new SkillCheckpointRecoveryCoordination(
                SkillCheckpointRecoveryCoordinationStatus
                        .RESTART_PLAN_UNAVAILABLE,
                decision.disposition(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }

    static SkillCheckpointRecoveryCoordination restartPlanRejected(
            SkillCheckpointRecoveryOutcome decision) {
        return new SkillCheckpointRecoveryCoordination(
                SkillCheckpointRecoveryCoordinationStatus
                        .RESTART_PLAN_REJECTED,
                decision.disposition(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty());
    }
}
