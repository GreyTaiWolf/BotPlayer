package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;

/** 判定结果将拒绝原因和可执行 continuation 明确互斥。 */
public record SkillCheckpointRecoveryOutcome(
        SkillCheckpointRecoveryDisposition disposition,
        Optional<SkillCheckpointRecoveryRejection> rejection,
        Optional<SkillCheckpointContinuation> continuation) {
    public SkillCheckpointRecoveryOutcome {
        Objects.requireNonNull(disposition, "disposition");
        rejection = Objects.requireNonNull(rejection, "rejection");
        continuation = Objects.requireNonNull(continuation, "continuation");
        if (disposition == SkillCheckpointRecoveryDisposition.REJECT
                && (rejection.isEmpty() || continuation.isPresent())) {
            throw new IllegalArgumentException(
                    "rejected recovery requires only a rejection reason");
        }
        if (disposition
                        == SkillCheckpointRecoveryDisposition
                                .RESTART_FROM_SAFE_PHASE
                && (rejection.isPresent() || continuation.isEmpty())) {
            throw new IllegalArgumentException(
                    "restart recovery requires only a continuation");
        }
    }

    static SkillCheckpointRecoveryOutcome reject(
            SkillCheckpointRecoveryRejection rejection) {
        return new SkillCheckpointRecoveryOutcome(
                SkillCheckpointRecoveryDisposition.REJECT,
                Optional.of(Objects.requireNonNull(rejection, "rejection")),
                Optional.empty());
    }

    static SkillCheckpointRecoveryOutcome restart(
            SkillCheckpointContinuation continuation) {
        return new SkillCheckpointRecoveryOutcome(
                SkillCheckpointRecoveryDisposition.RESTART_FROM_SAFE_PHASE,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(continuation, "continuation")));
    }
}
