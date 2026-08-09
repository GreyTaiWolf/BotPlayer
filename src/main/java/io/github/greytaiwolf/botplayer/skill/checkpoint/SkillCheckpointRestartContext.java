package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 供重启计划 provider 使用的缩减 continuation。
 *
 * <p>checkpointId 可能在旧格式中与旧 runId 同值，因此这里故意既不暴露 checkpointId，
 * 也不暴露 runId。它只保留重新观察和重新规划真正需要的持久事实。
 */
public record SkillCheckpointRestartContext(
        UUID botId,
        UUID playerId,
        long generation,
        SkillCheckpointPlan plan,
        String safePhase,
        long sourceStateRevision,
        List<SkillCheckpointNode> nodes,
        Optional<SkillCheckpointScope> scope,
        List<CheckpointEvidence> checkpointEvidence,
        List<CheckpointEvidence> reobservationEvidence) {
    public SkillCheckpointRestartContext {
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        CheckpointNbt.requireNonZeroUuid(playerId, "playerId");
        if (generation <= 0L || sourceStateRevision < 0L) {
            throw new IllegalArgumentException(
                    "generation must be positive and sourceStateRevision non-negative");
        }
        Objects.requireNonNull(plan, "plan");
        CheckpointNbt.requirePhase(safePhase);
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty() || nodes.size() > SkillCheckpoint.MAX_NODES) {
            throw new IllegalArgumentException("restart nodes exceed safe bounds");
        }
        java.util.Set<UUID> nodeIds = new HashSet<>();
        for (SkillCheckpointNode node : nodes) {
            if (!nodeIds.add(Objects.requireNonNull(node, "node").nodeId())) {
                throw new IllegalArgumentException(
                        "restart nodes contain a duplicate nodeId");
            }
        }
        scope = Objects.requireNonNull(scope, "scope");
        checkpointEvidence = boundedEvidence(
                checkpointEvidence, "checkpointEvidence", false);
        reobservationEvidence = boundedEvidence(
                reobservationEvidence, "reobservationEvidence", true);
    }

    static SkillCheckpointRestartContext from(
            SkillCheckpointContinuation continuation) {
        Objects.requireNonNull(continuation, "continuation");
        return new SkillCheckpointRestartContext(
                continuation.botId(),
                continuation.playerId(),
                continuation.generation(),
                continuation.plan(),
                continuation.safePhase(),
                continuation.sourceStateRevision(),
                continuation.nodes(),
                continuation.scope(),
                continuation.checkpointEvidence(),
                continuation.reobservationEvidence());
    }

    private static List<CheckpointEvidence> boundedEvidence(
            List<CheckpointEvidence> values,
            String name,
            boolean requireNonEmpty) {
        List<CheckpointEvidence> copy = List.copyOf(
                Objects.requireNonNull(values, name));
        if (copy.size() > SkillCheckpoint.MAX_EVIDENCE
                || (requireNonEmpty && copy.isEmpty())) {
            throw new IllegalArgumentException(
                    name + " does not satisfy restart evidence bounds");
        }
        return copy;
    }
}
