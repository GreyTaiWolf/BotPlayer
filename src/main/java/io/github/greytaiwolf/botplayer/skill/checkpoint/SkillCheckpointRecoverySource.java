package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;

/** 检查点读取结果与原始记录成对传递，避免调用者掩盖损坏或版本错误。 */
public record SkillCheckpointRecoverySource(
        SkillCheckpointLoadStatus loadStatus,
        Optional<SkillCheckpoint> checkpoint) {
    public SkillCheckpointRecoverySource {
        Objects.requireNonNull(loadStatus, "loadStatus");
        checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        if (loadStatus == SkillCheckpointLoadStatus.VALID
                && checkpoint.isEmpty()) {
            throw new IllegalArgumentException(
                    "valid checkpoint source requires a checkpoint");
        }
        if (loadStatus != SkillCheckpointLoadStatus.VALID
                && checkpoint.isPresent()) {
            throw new IllegalArgumentException(
                    "invalid checkpoint source must not expose checkpoint data");
        }
    }

    public static SkillCheckpointRecoverySource valid(
            SkillCheckpoint checkpoint) {
        return new SkillCheckpointRecoverySource(
                SkillCheckpointLoadStatus.VALID,
                Optional.of(Objects.requireNonNull(checkpoint, "checkpoint")));
    }

    public static SkillCheckpointRecoverySource unavailable(
            SkillCheckpointLoadStatus loadStatus) {
        if (loadStatus == SkillCheckpointLoadStatus.VALID) {
            throw new IllegalArgumentException(
                    "valid status requires a checkpoint");
        }
        return new SkillCheckpointRecoverySource(loadStatus, Optional.empty());
    }
}
