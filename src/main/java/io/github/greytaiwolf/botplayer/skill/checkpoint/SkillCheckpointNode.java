package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/** 单个 DAG 节点的可恢复状态，不保存动作、菜单或物品对象。 */
public record SkillCheckpointNode(
        UUID nodeId,
        SkillCheckpointNodeState state,
        int attemptCount,
        Optional<String> failureCode,
        List<CheckpointEvidence> evidence) {
    public static final int MAX_ATTEMPTS = 128;
    public static final int MAX_EVIDENCE = 8;

    private static final String NODE_ID_TAG = "NodeId";
    private static final String STATE_TAG = "State";
    private static final String ATTEMPT_COUNT_TAG = "AttemptCount";
    private static final String FAILURE_CODE_TAG = "FailureCode";
    private static final String EVIDENCE_TAG = "Evidence";

    public SkillCheckpointNode {
        CheckpointNbt.requireNonZeroUuid(nodeId, "nodeId");
        Objects.requireNonNull(state, "state");
        if (attemptCount < 0 || attemptCount > MAX_ATTEMPTS) {
            throw new IllegalArgumentException(
                    "attemptCount must be between 0 and " + MAX_ATTEMPTS);
        }
        failureCode = Objects.requireNonNull(failureCode, "failureCode");
        failureCode.ifPresent(value -> CheckpointNbt.requireCode(value, "failureCode"));
        if (state == SkillCheckpointNodeState.FAILED && failureCode.isEmpty()) {
            throw new IllegalArgumentException("failed node requires a failureCode");
        }
        if (state != SkillCheckpointNodeState.FAILED && failureCode.isPresent()) {
            throw new IllegalArgumentException("only failed node may expose a failureCode");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "evidence exceeds maximum " + MAX_EVIDENCE);
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CheckpointNbt.putUuid(tag, NODE_ID_TAG, nodeId);
        tag.putString(STATE_TAG, state.name());
        tag.putInt(ATTEMPT_COUNT_TAG, attemptCount);
        failureCode.ifPresent(value -> tag.putString(FAILURE_CODE_TAG, value));
        ListTag evidenceTag = new ListTag();
        for (CheckpointEvidence value : evidence) {
            evidenceTag.add(value.save());
        }
        tag.put(EVIDENCE_TAG, evidenceTag);
        return tag;
    }

    static SkillCheckpointNode load(CompoundTag tag) {
        String subject = "checkpoint node";
        CheckpointNbt.requireExactKeys(
                tag,
                java.util.Set.of(
                        NODE_ID_TAG,
                        STATE_TAG,
                        ATTEMPT_COUNT_TAG,
                        EVIDENCE_TAG),
                java.util.Set.of(FAILURE_CODE_TAG),
                subject);
        CheckpointNbt.requireType(tag, STATE_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(tag, ATTEMPT_COUNT_TAG, Tag.TAG_INT, subject);
        CheckpointNbt.requireType(tag, EVIDENCE_TAG, Tag.TAG_LIST, subject);
        if (tag.contains(FAILURE_CODE_TAG)) {
            CheckpointNbt.requireType(
                    tag, FAILURE_CODE_TAG, Tag.TAG_STRING, subject);
        }
        ListTag evidenceTag = (ListTag) Objects.requireNonNull(
                tag.get(EVIDENCE_TAG), EVIDENCE_TAG);
        if (evidenceTag.size() > MAX_EVIDENCE) {
            throw CheckpointNbt.invalid(subject, "evidence exceeds its maximum", null);
        }
        List<CheckpointEvidence> evidence = new ArrayList<>(evidenceTag.size());
        for (Tag encoded : evidenceTag) {
            if (!(encoded instanceof CompoundTag evidenceCompound)) {
                throw CheckpointNbt.invalid(subject, "evidence contains a non-compound entry", null);
            }
            evidence.add(CheckpointEvidence.load(evidenceCompound));
        }
        try {
            return new SkillCheckpointNode(
                    CheckpointNbt.readUuid(tag, NODE_ID_TAG, subject),
                    SkillCheckpointNodeState.valueOf(tag.getString(STATE_TAG)),
                    tag.getInt(ATTEMPT_COUNT_TAG),
                    tag.contains(FAILURE_CODE_TAG)
                            ? Optional.of(tag.getString(FAILURE_CODE_TAG))
                            : Optional.empty(),
                    evidence);
        } catch (IllegalArgumentException exception) {
            throw CheckpointNbt.invalid(subject, "fields are invalid", exception);
        }
    }
}
