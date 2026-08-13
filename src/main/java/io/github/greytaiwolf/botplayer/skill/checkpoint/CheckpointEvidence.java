package io.github.greytaiwolf.botplayer.skill.checkpoint;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 可公开诊断、长度受限且不携带 NBT 或秘密的检查点证据。 */
public record CheckpointEvidence(String code, String summary, long observedTick) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    private static final String CODE_TAG = "Code";
    private static final String SUMMARY_TAG = "Summary";
    private static final String OBSERVED_TICK_TAG = "ObservedTick";

    public CheckpointEvidence {
        CheckpointNbt.requireCode(code, "code");
        CheckpointNbt.requireSummary(summary, "summary", MAX_SUMMARY_LENGTH);
        if (observedTick < 0L) {
            throw new IllegalArgumentException("observedTick must not be negative");
        }
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(CODE_TAG, code);
        tag.putString(SUMMARY_TAG, summary);
        tag.putLong(OBSERVED_TICK_TAG, observedTick);
        return tag;
    }

    static CheckpointEvidence load(CompoundTag tag) {
        String subject = "checkpoint evidence";
        CheckpointNbt.requireExactKeys(
                tag,
                java.util.Set.of(CODE_TAG, SUMMARY_TAG, OBSERVED_TICK_TAG),
                java.util.Set.of(),
                subject);
        CheckpointNbt.requireType(tag, CODE_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(tag, SUMMARY_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(tag, OBSERVED_TICK_TAG, Tag.TAG_LONG, subject);
        try {
            return new CheckpointEvidence(
                    tag.getString(CODE_TAG),
                    tag.getString(SUMMARY_TAG),
                    tag.getLong(OBSERVED_TICK_TAG));
        } catch (IllegalArgumentException exception) {
            throw CheckpointNbt.invalid(subject, "fields are out of bounds", exception);
        }
    }
}
