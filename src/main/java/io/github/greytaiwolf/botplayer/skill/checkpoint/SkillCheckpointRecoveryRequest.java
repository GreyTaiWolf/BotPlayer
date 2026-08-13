package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.UUID;

/** 恢复判定所需的全部当前事实；不接收任何活 Minecraft 对象。 */
public record SkillCheckpointRecoveryRequest(
        SkillCheckpointRecoverySource source,
        UUID serverInstanceId,
        UUID botId,
        UUID playerId,
        long newGeneration,
        SkillCheckpointRecoveryAvailability availability,
        SkillCheckpointRecoverySafety safety,
        SkillCheckpointReobservation reobservation) {
    public SkillCheckpointRecoveryRequest {
        Objects.requireNonNull(source, "source");
        CheckpointNbt.requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        CheckpointNbt.requireNonZeroUuid(playerId, "playerId");
        if (newGeneration <= 0L) {
            throw new IllegalArgumentException("newGeneration must be positive");
        }
        Objects.requireNonNull(availability, "availability");
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(reobservation, "reobservation");
    }
}
