package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;

/** 严格后缀构建结果；失败原因不含未受限的计划或存档正文。 */
public record SkillCheckpointRestartPlanBuild(
        Status status, Optional<SkillCheckpointRestartPlan> restartPlan) {
    public enum Status {
        BUILT,
        SOURCE_PLAN_MISMATCH,
        FULL_PLAN_EMPTY,
        FULL_PLAN_DUPLICATE_NODE,
        CHECKPOINT_NODE_SET_MISMATCH,
        FAILED_NODE_PRESENT,
        INVALID_EDGE,
        DUPLICATE_EDGE,
        CYCLIC_PLAN,
        COMPLETED_NOT_PREFIX,
        COMPLETED_DEPENDS_ON_SUFFIX,
        EMPTY_SUFFIX
    }

    public SkillCheckpointRestartPlanBuild {
        Objects.requireNonNull(status, "status");
        restartPlan = Objects.requireNonNull(restartPlan, "restartPlan");
        if ((status == Status.BUILT) != restartPlan.isPresent()) {
            throw new IllegalArgumentException(
                    "only a built restart plan may expose a plan");
        }
    }

    static SkillCheckpointRestartPlanBuild built(
            SkillCheckpointRestartPlan restartPlan) {
        return new SkillCheckpointRestartPlanBuild(
                Status.BUILT,
                Optional.of(Objects.requireNonNull(restartPlan, "restartPlan")));
    }

    static SkillCheckpointRestartPlanBuild rejected(Status status) {
        if (Objects.requireNonNull(status, "status") == Status.BUILT) {
            throw new IllegalArgumentException("built status requires a plan");
        }
        return new SkillCheckpointRestartPlanBuild(status, Optional.empty());
    }
}
