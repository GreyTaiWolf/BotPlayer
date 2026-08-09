package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** P5A 安全点的完整纯数据快照。 */
public record SkillCheckpoint(
        UUID checkpointId,
        UUID serverInstanceId,
        UUID botId,
        UUID playerId,
        long generation,
        UUID runId,
        SkillCheckpointPlan plan,
        String phase,
        SkillCheckpointContinuationState continuationState,
        long stateRevision,
        int attemptCount,
        int recoveryCount,
        long checkpointTick,
        Optional<SkillCheckpointScope> scope,
        List<SkillCheckpointNode> nodes,
        List<CheckpointEvidence> evidence,
        Optional<String> failureCode) {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_NODES = 256;
    public static final int MAX_EVIDENCE = 16;
    public static final int MAX_ATTEMPTS = 128;
    public static final int MAX_RECOVERIES = 32;

    private static final String SCHEMA_VERSION_TAG = "SchemaVersion";
    private static final String CHECKPOINT_ID_TAG = "CheckpointId";
    private static final String SERVER_INSTANCE_ID_TAG = "ServerInstanceId";
    private static final String BOT_ID_TAG = "BotId";
    private static final String PLAYER_ID_TAG = "PlayerId";
    private static final String GENERATION_TAG = "Generation";
    private static final String RUN_ID_TAG = "RunId";
    private static final String PLAN_TAG = "Plan";
    private static final String PHASE_TAG = "Phase";
    private static final String CONTINUATION_STATE_TAG = "ContinuationState";
    private static final String STATE_REVISION_TAG = "StateRevision";
    private static final String ATTEMPT_COUNT_TAG = "AttemptCount";
    private static final String RECOVERY_COUNT_TAG = "RecoveryCount";
    private static final String CHECKPOINT_TICK_TAG = "CheckpointTick";
    private static final String SCOPE_TAG = "Scope";
    private static final String NODES_TAG = "Nodes";
    private static final String EVIDENCE_TAG = "Evidence";
    private static final String FAILURE_CODE_TAG = "FailureCode";
    private static final String INTEGRITY_TAG = "Integrity";

    public SkillCheckpoint {
        CheckpointNbt.requireNonZeroUuid(checkpointId, "checkpointId");
        CheckpointNbt.requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        CheckpointNbt.requireNonZeroUuid(botId, "botId");
        CheckpointNbt.requireNonZeroUuid(playerId, "playerId");
        if (generation <= 0L) {
            throw new IllegalArgumentException("generation must be positive");
        }
        CheckpointNbt.requireNonZeroUuid(runId, "runId");
        Objects.requireNonNull(plan, "plan");
        CheckpointNbt.requirePhase(phase);
        Objects.requireNonNull(continuationState, "continuationState");
        if (stateRevision < 0L
                || attemptCount < 0
                || attemptCount > MAX_ATTEMPTS
                || recoveryCount < 0
                || recoveryCount > MAX_RECOVERIES
                || checkpointTick < 0L) {
            throw new IllegalArgumentException("checkpoint counters are outside safe bounds");
        }
        scope = Objects.requireNonNull(scope, "scope");
        nodes = canonicalNodes(nodes);
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "evidence exceeds maximum " + MAX_EVIDENCE);
        }
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        failureCode.ifPresent(value -> CheckpointNbt.requireCode(value, "failureCode"));
        if (continuationState == SkillCheckpointContinuationState.FAILED
                && failureCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "failed checkpoint requires a failureCode");
        }
        if (continuationState != SkillCheckpointContinuationState.FAILED
                && failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "only failed checkpoint may expose a failureCode");
        }
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(SCHEMA_VERSION_TAG, SCHEMA_VERSION);
        CheckpointNbt.putUuid(tag, CHECKPOINT_ID_TAG, checkpointId);
        CheckpointNbt.putUuid(tag, SERVER_INSTANCE_ID_TAG, serverInstanceId);
        CheckpointNbt.putUuid(tag, BOT_ID_TAG, botId);
        CheckpointNbt.putUuid(tag, PLAYER_ID_TAG, playerId);
        tag.putLong(GENERATION_TAG, generation);
        CheckpointNbt.putUuid(tag, RUN_ID_TAG, runId);
        tag.put(PLAN_TAG, plan.save());
        tag.putString(PHASE_TAG, phase);
        tag.putString(CONTINUATION_STATE_TAG, continuationState.name());
        tag.putLong(STATE_REVISION_TAG, stateRevision);
        tag.putInt(ATTEMPT_COUNT_TAG, attemptCount);
        tag.putInt(RECOVERY_COUNT_TAG, recoveryCount);
        tag.putLong(CHECKPOINT_TICK_TAG, checkpointTick);
        scope.ifPresent(value -> tag.put(SCOPE_TAG, value.save()));
        ListTag nodesTag = new ListTag();
        for (SkillCheckpointNode node : nodes) {
            nodesTag.add(node.save());
        }
        tag.put(NODES_TAG, nodesTag);
        ListTag evidenceTag = new ListTag();
        for (CheckpointEvidence value : evidence) {
            evidenceTag.add(value.save());
        }
        tag.put(EVIDENCE_TAG, evidenceTag);
        failureCode.ifPresent(value -> tag.putString(FAILURE_CODE_TAG, value));
        CheckpointNbt.writeIntegrity(tag, INTEGRITY_TAG);
        return tag;
    }

    public static SkillCheckpoint load(CompoundTag tag) {
        String subject = "skill checkpoint";
        CheckpointNbt.requireExactKeys(
                tag,
                Set.of(
                        SCHEMA_VERSION_TAG,
                        CHECKPOINT_ID_TAG,
                        SERVER_INSTANCE_ID_TAG,
                        BOT_ID_TAG,
                        PLAYER_ID_TAG,
                        GENERATION_TAG,
                        RUN_ID_TAG,
                        PLAN_TAG,
                        PHASE_TAG,
                        CONTINUATION_STATE_TAG,
                        STATE_REVISION_TAG,
                        ATTEMPT_COUNT_TAG,
                        RECOVERY_COUNT_TAG,
                        CHECKPOINT_TICK_TAG,
                        NODES_TAG,
                        EVIDENCE_TAG,
                        INTEGRITY_TAG),
                Set.of(SCOPE_TAG, FAILURE_CODE_TAG),
                subject);
        CheckpointNbt.requireType(tag, SCHEMA_VERSION_TAG, Tag.TAG_INT, subject);
        if (tag.getInt(SCHEMA_VERSION_TAG) != SCHEMA_VERSION) {
            throw CheckpointNbt.invalid(subject, "schema version is unsupported", null);
        }
        CheckpointNbt.requireType(tag, GENERATION_TAG, Tag.TAG_LONG, subject);
        CheckpointNbt.requireType(tag, PLAN_TAG, Tag.TAG_COMPOUND, subject);
        CheckpointNbt.requireType(tag, PHASE_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(
                tag, CONTINUATION_STATE_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(tag, STATE_REVISION_TAG, Tag.TAG_LONG, subject);
        CheckpointNbt.requireType(tag, ATTEMPT_COUNT_TAG, Tag.TAG_INT, subject);
        CheckpointNbt.requireType(tag, RECOVERY_COUNT_TAG, Tag.TAG_INT, subject);
        CheckpointNbt.requireType(tag, CHECKPOINT_TICK_TAG, Tag.TAG_LONG, subject);
        CheckpointNbt.requireType(tag, NODES_TAG, Tag.TAG_LIST, subject);
        CheckpointNbt.requireType(tag, EVIDENCE_TAG, Tag.TAG_LIST, subject);
        if (tag.contains(SCOPE_TAG)) {
            CheckpointNbt.requireType(tag, SCOPE_TAG, Tag.TAG_COMPOUND, subject);
        }
        if (tag.contains(FAILURE_CODE_TAG)) {
            CheckpointNbt.requireType(tag, FAILURE_CODE_TAG, Tag.TAG_STRING, subject);
        }
        UUID checkpointId = CheckpointNbt.readUuid(
                tag, CHECKPOINT_ID_TAG, subject);
        UUID serverInstanceId = CheckpointNbt.readUuid(
                tag, SERVER_INSTANCE_ID_TAG, subject);
        UUID botId = CheckpointNbt.readUuid(tag, BOT_ID_TAG, subject);
        UUID playerId = CheckpointNbt.readUuid(tag, PLAYER_ID_TAG, subject);
        UUID runId = CheckpointNbt.readUuid(tag, RUN_ID_TAG, subject);
        String phase;
        SkillCheckpointContinuationState continuationState;
        Optional<String> failureCode;
        try {
            phase = tag.getString(PHASE_TAG);
            CheckpointNbt.requirePhase(phase);
            continuationState = SkillCheckpointContinuationState.valueOf(
                    tag.getString(CONTINUATION_STATE_TAG));
            failureCode = tag.contains(FAILURE_CODE_TAG)
                    ? Optional.of(tag.getString(FAILURE_CODE_TAG))
                    : Optional.empty();
            failureCode.ifPresent(value ->
                    CheckpointNbt.requireCode(value, "failureCode"));
        } catch (IllegalArgumentException exception) {
            throw CheckpointNbt.invalid(subject, "state fields are invalid", exception);
        }
        List<SkillCheckpointNode> nodes = loadNodes(tag, subject);
        List<CheckpointEvidence> evidence = loadEvidence(tag, subject);
        SkillCheckpointPlan plan = SkillCheckpointPlan.load((CompoundTag)
                Objects.requireNonNull(tag.get(PLAN_TAG), PLAN_TAG));
        Optional<SkillCheckpointScope> scope = tag.contains(SCOPE_TAG)
                ? Optional.of(SkillCheckpointScope.load((CompoundTag)
                        Objects.requireNonNull(tag.get(SCOPE_TAG), SCOPE_TAG)))
                : Optional.empty();
        // 先限制所有嵌套结构，再哈希，避免损坏存档把完整递归树带入摘要计算。
        CheckpointNbt.verifyIntegrity(tag, INTEGRITY_TAG, subject);
        try {
            return new SkillCheckpoint(
                    checkpointId,
                    serverInstanceId,
                    botId,
                    playerId,
                    tag.getLong(GENERATION_TAG),
                    runId,
                    plan,
                    phase,
                    continuationState,
                    tag.getLong(STATE_REVISION_TAG),
                    tag.getInt(ATTEMPT_COUNT_TAG),
                    tag.getInt(RECOVERY_COUNT_TAG),
                    tag.getLong(CHECKPOINT_TICK_TAG),
                    scope,
                    nodes,
                    evidence,
                    failureCode);
        } catch (IllegalArgumentException exception) {
            throw CheckpointNbt.invalid(subject, "fields are invalid", exception);
        }
    }

    private static List<SkillCheckpointNode> canonicalNodes(
            List<SkillCheckpointNode> values) {
        Objects.requireNonNull(values, "nodes");
        if (values.isEmpty() || values.size() > MAX_NODES) {
            throw new IllegalArgumentException(
                    "nodes must contain 1-" + MAX_NODES + " entries");
        }
        List<SkillCheckpointNode> sorted = new ArrayList<>(values);
        sorted.sort(Comparator.comparing(SkillCheckpointNode::nodeId));
        Set<UUID> ids = new HashSet<>();
        for (SkillCheckpointNode value : sorted) {
            Objects.requireNonNull(value, "node");
            if (!ids.add(value.nodeId())) {
                throw new IllegalArgumentException("nodes contains a duplicate nodeId");
            }
        }
        return List.copyOf(sorted);
    }

    private static List<SkillCheckpointNode> loadNodes(
            CompoundTag tag, String subject) {
        ListTag encoded = (ListTag) Objects.requireNonNull(
                tag.get(NODES_TAG), NODES_TAG);
        if (encoded.isEmpty() || encoded.size() > MAX_NODES) {
            throw CheckpointNbt.invalid(subject, "nodes exceed their safe bound", null);
        }
        List<SkillCheckpointNode> result = new ArrayList<>(encoded.size());
        for (Tag value : encoded) {
            if (!(value instanceof CompoundTag nodeTag)) {
                throw CheckpointNbt.invalid(subject, "nodes contain a non-compound entry", null);
            }
            result.add(SkillCheckpointNode.load(nodeTag));
        }
        return result;
    }

    private static List<CheckpointEvidence> loadEvidence(
            CompoundTag tag, String subject) {
        ListTag encoded = (ListTag) Objects.requireNonNull(
                tag.get(EVIDENCE_TAG), EVIDENCE_TAG);
        if (encoded.size() > MAX_EVIDENCE) {
            throw CheckpointNbt.invalid(subject, "evidence exceeds its safe bound", null);
        }
        List<CheckpointEvidence> result = new ArrayList<>(encoded.size());
        for (Tag value : encoded) {
            if (!(value instanceof CompoundTag evidenceTag)) {
                throw CheckpointNbt.invalid(subject, "evidence contains a non-compound entry", null);
            }
            result.add(CheckpointEvidence.load(evidenceTag));
        }
        return result;
    }
}
