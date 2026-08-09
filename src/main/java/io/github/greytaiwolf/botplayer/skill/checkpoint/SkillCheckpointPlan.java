package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 已审核计划的稳定引用；正文和可执行对象不进入检查点。 */
public record SkillCheckpointPlan(
        UUID planId, long revision, String contentDigest) {
    private static final String PLAN_ID_TAG = "PlanId";
    private static final String REVISION_TAG = "Revision";
    private static final String CONTENT_DIGEST_TAG = "ContentDigest";

    public SkillCheckpointPlan {
        CheckpointNbt.requireNonZeroUuid(planId, "planId");
        if (revision <= 0L) {
            throw new IllegalArgumentException("revision must be positive");
        }
        CheckpointNbt.requireSha256(contentDigest, "contentDigest");
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        CheckpointNbt.putUuid(tag, PLAN_ID_TAG, planId);
        tag.putLong(REVISION_TAG, revision);
        tag.putString(CONTENT_DIGEST_TAG, contentDigest);
        return tag;
    }

    static SkillCheckpointPlan load(CompoundTag tag) {
        String subject = "checkpoint plan reference";
        CheckpointNbt.requireExactKeys(
                tag,
                java.util.Set.of(PLAN_ID_TAG, REVISION_TAG, CONTENT_DIGEST_TAG),
                java.util.Set.of(),
                subject);
        CheckpointNbt.requireType(tag, REVISION_TAG, Tag.TAG_LONG, subject);
        CheckpointNbt.requireType(tag, CONTENT_DIGEST_TAG, Tag.TAG_STRING, subject);
        try {
            return new SkillCheckpointPlan(
                    CheckpointNbt.readUuid(tag, PLAN_ID_TAG, subject),
                    tag.getLong(REVISION_TAG),
                    tag.getString(CONTENT_DIGEST_TAG));
        } catch (IllegalArgumentException exception) {
            throw CheckpointNbt.invalid(subject, "fields are invalid", exception);
        }
    }
}
