package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 已获准的新 generation continuation。
 *
 * <p>这里刻意不携带旧 runId、旧 generation token、动作、菜单或租约；运行时必须重新分配
 * runId 并重新取得所有瞬态资源。
 */
public record SkillCheckpointContinuation(
        UUID checkpointId,
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
    public SkillCheckpointContinuation {
        CheckpointNbt.requireNonZeroUuid(checkpointId, "checkpointId");
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        CheckpointNbt.requireNonZeroUuid(playerId, "playerId");
        if (generation <= 0L || sourceStateRevision < 0L) {
            throw new IllegalArgumentException(
                    "continuation counters must be non-negative and generation positive");
        }
        Objects.requireNonNull(plan, "plan");
        CheckpointNbt.requirePhase(safePhase);
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (nodes.isEmpty() || nodes.size() > SkillCheckpoint.MAX_NODES) {
            throw new IllegalArgumentException("continuation nodes exceed safe bounds");
        }
        java.util.Set<UUID> nodeIds = new HashSet<>();
        for (SkillCheckpointNode node : nodes) {
            if (!nodeIds.add(node.nodeId())) {
                throw new IllegalArgumentException(
                        "continuation nodes contain a duplicate nodeId");
            }
        }
        scope = Objects.requireNonNull(scope, "scope");
        checkpointEvidence = boundedEvidence(
                checkpointEvidence, "checkpointEvidence");
        reobservationEvidence = boundedEvidence(
                reobservationEvidence, "reobservationEvidence");
        if (reobservationEvidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "continuation requires reobservation evidence");
        }
    }

    private static List<CheckpointEvidence> boundedEvidence(
            List<CheckpointEvidence> values, String name) {
        List<CheckpointEvidence> copy = List.copyOf(
                Objects.requireNonNull(values, name));
        if (copy.size() > SkillCheckpoint.MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    name + " exceeds safe evidence bound");
        }
        return copy;
    }
}
