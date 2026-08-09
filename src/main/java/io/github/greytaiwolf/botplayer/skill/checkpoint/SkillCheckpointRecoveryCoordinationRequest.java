package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 重启恢复编排所需的当前事实。
 *
 * <p>{@code approvedPlan} 必须来自当前仍被批准的 pack/内建注册表，而不是 checkpoint
 * 中序列化出来的正文。协调器会重新计算其摘要并交给判定器核验。
 */
public record SkillCheckpointRecoveryCoordinationRequest(
        SkillCheckpointRecoverySource source,
        UUID serverInstanceId,
        UUID botId,
        UUID playerId,
        long newGeneration,
        Optional<SkillPlan> approvedPlan,
        boolean allDescriptorsAvailable,
        SkillCheckpointRecoverySafety safety,
        SkillCheckpointReobservation reobservation,
        long submittedTick) {
    public SkillCheckpointRecoveryCoordinationRequest {
        Objects.requireNonNull(source, "source");
        CheckpointNbt.requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        CheckpointNbt.requireNonZeroUuid(playerId, "playerId");
        if (newGeneration <= 0L) {
            throw new IllegalArgumentException("newGeneration must be positive");
        }
        approvedPlan = Objects.requireNonNull(approvedPlan, "approvedPlan");
        approvedPlan.ifPresent(plan -> {
            if (!botId.equals(plan.botId())) {
                throw new IllegalArgumentException(
                        "approvedPlan botId must equal recovery botId");
            }
        });
        Objects.requireNonNull(safety, "safety");
        Objects.requireNonNull(reobservation, "reobservation");
        if (submittedTick < 0L) {
            throw new IllegalArgumentException("submittedTick must not be negative");
        }
    }
}
