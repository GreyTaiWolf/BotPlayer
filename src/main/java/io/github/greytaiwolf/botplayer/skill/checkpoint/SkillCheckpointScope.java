package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** 已重新验证的有限世界位置和摘要，不保留活世界对象。 */
public record SkillCheckpointScope(
        String dimensionId,
        int blockX,
        int blockY,
        int blockZ,
        Optional<UUID> targetEntityId,
        String expectedFingerprint,
        String verifiedSummary) {
    public static final int MAX_ABSOLUTE_COORDINATE = 30_000_000;
    public static final int MAX_VERIFIED_SUMMARY_LENGTH = 256;

    private static final String DIMENSION_ID_TAG = "DimensionId";
    private static final String BLOCK_X_TAG = "BlockX";
    private static final String BLOCK_Y_TAG = "BlockY";
    private static final String BLOCK_Z_TAG = "BlockZ";
    private static final String TARGET_ENTITY_ID_TAG = "TargetEntityId";
    private static final String EXPECTED_FINGERPRINT_TAG = "ExpectedFingerprint";
    private static final String VERIFIED_SUMMARY_TAG = "VerifiedSummary";

    public SkillCheckpointScope {
        CheckpointNbt.requireResourceId(dimensionId, "dimensionId");
        requireCoordinate(blockX, "blockX");
        requireCoordinate(blockY, "blockY");
        requireCoordinate(blockZ, "blockZ");
        targetEntityId = Objects.requireNonNull(targetEntityId, "targetEntityId");
        targetEntityId.ifPresent(value ->
                CheckpointNbt.requireNonZeroUuid(value, "targetEntityId"));
        CheckpointNbt.requireSha256(expectedFingerprint, "expectedFingerprint");
        CheckpointNbt.requireSummary(
                verifiedSummary,
                "verifiedSummary",
                MAX_VERIFIED_SUMMARY_LENGTH);
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString(DIMENSION_ID_TAG, dimensionId);
        tag.putInt(BLOCK_X_TAG, blockX);
        tag.putInt(BLOCK_Y_TAG, blockY);
        tag.putInt(BLOCK_Z_TAG, blockZ);
        targetEntityId.ifPresent(value ->
                CheckpointNbt.putUuid(tag, TARGET_ENTITY_ID_TAG, value));
        tag.putString(EXPECTED_FINGERPRINT_TAG, expectedFingerprint);
        tag.putString(VERIFIED_SUMMARY_TAG, verifiedSummary);
        return tag;
    }

    static SkillCheckpointScope load(CompoundTag tag) {
        String subject = "checkpoint scope";
        CheckpointNbt.requireExactKeys(
                tag,
                java.util.Set.of(
                        DIMENSION_ID_TAG,
                        BLOCK_X_TAG,
                        BLOCK_Y_TAG,
                        BLOCK_Z_TAG,
                        EXPECTED_FINGERPRINT_TAG,
                        VERIFIED_SUMMARY_TAG),
                java.util.Set.of(TARGET_ENTITY_ID_TAG),
                subject);
        CheckpointNbt.requireType(tag, DIMENSION_ID_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(tag, BLOCK_X_TAG, Tag.TAG_INT, subject);
        CheckpointNbt.requireType(tag, BLOCK_Y_TAG, Tag.TAG_INT, subject);
        CheckpointNbt.requireType(tag, BLOCK_Z_TAG, Tag.TAG_INT, subject);
        CheckpointNbt.requireType(
                tag, EXPECTED_FINGERPRINT_TAG, Tag.TAG_STRING, subject);
        CheckpointNbt.requireType(tag, VERIFIED_SUMMARY_TAG, Tag.TAG_STRING, subject);
        if (tag.contains(TARGET_ENTITY_ID_TAG)) {
            CheckpointNbt.requireType(
                    tag, TARGET_ENTITY_ID_TAG, Tag.TAG_INT_ARRAY, subject);
        }
        try {
            return new SkillCheckpointScope(
                    tag.getString(DIMENSION_ID_TAG),
                    tag.getInt(BLOCK_X_TAG),
                    tag.getInt(BLOCK_Y_TAG),
                    tag.getInt(BLOCK_Z_TAG),
                    tag.contains(TARGET_ENTITY_ID_TAG)
                            ? Optional.of(CheckpointNbt.readUuid(
                                    tag, TARGET_ENTITY_ID_TAG, subject))
                            : Optional.empty(),
                    tag.getString(EXPECTED_FINGERPRINT_TAG),
                    tag.getString(VERIFIED_SUMMARY_TAG));
        } catch (IllegalArgumentException exception) {
            throw CheckpointNbt.invalid(subject, "fields are invalid", exception);
        }
    }

    private static void requireCoordinate(int coordinate, String name) {
        if (coordinate < -MAX_ABSOLUTE_COORDINATE
                || coordinate > MAX_ABSOLUTE_COORDINATE) {
            throw new IllegalArgumentException(
                    name + " exceeds the supported world bound");
        }
    }
}
