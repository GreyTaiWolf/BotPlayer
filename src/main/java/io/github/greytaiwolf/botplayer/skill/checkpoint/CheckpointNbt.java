package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/** 检查点 NBT 编解码共用的严格校验辅助。 */
final class CheckpointNbt {
    static final Pattern SAFE_CODE =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    static final Pattern SAFE_PHASE =
            Pattern.compile("[a-z][a-z0-9_.-]{0,95}");
    static final Pattern RESOURCE_ID = Pattern.compile(
            "[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}");
    static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    private CheckpointNbt() {}

    static void requireNonZeroUuid(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
    }

    static void requireCode(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SAFE_CODE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must use a bounded lower-case code");
        }
    }

    static void requirePhase(String value) {
        Objects.requireNonNull(value, "phase");
        if (!SAFE_PHASE.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "phase must use a bounded lower-case code");
        }
    }

    static void requireResourceId(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!RESOURCE_ID.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must use a lower-case resource id");
        }
    }

    static void requireSha256(String value, String name) {
        Objects.requireNonNull(value, name);
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must be a lower-case SHA-256 digest");
        }
    }

    static void requireSummary(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty()
                || value.length() > maximumLength
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    name + " must be a trimmed bounded non-control summary");
        }
    }

    static void putUuid(CompoundTag tag, String key, UUID value) {
        tag.put(key, NbtUtils.createUUID(value));
    }

    static UUID readUuid(CompoundTag tag, String key, String subject) {
        requireType(tag, key, Tag.TAG_INT_ARRAY, subject);
        try {
            UUID value = NbtUtils.loadUUID(
                    Objects.requireNonNull(tag.get(key), key));
            requireNonZeroUuid(value, key);
            return value;
        } catch (IllegalArgumentException exception) {
            throw invalid(subject, key + " is not a valid UUID", exception);
        }
    }

    static void requireType(
            CompoundTag tag, String key, byte type, String subject) {
        if (!tag.contains(key, type)) {
            throw invalid(subject, key + " has a missing or invalid type", null);
        }
    }

    static void requireExactKeys(
            CompoundTag tag,
            Set<String> required,
            Set<String> optional,
            String subject) {
        Objects.requireNonNull(tag, "tag");
        for (String key : required) {
            if (!tag.contains(key)) {
                throw invalid(subject, "is missing required field " + key, null);
            }
        }
        Set<String> allowed = new java.util.HashSet<>(required);
        allowed.addAll(optional);
        List<String> unexpected = new ArrayList<>(tag.getAllKeys());
        unexpected.removeAll(allowed);
        if (!unexpected.isEmpty()) {
            unexpected.sort(String::compareTo);
            throw invalid(
                    subject,
                    "contains unexpected fields: " + unexpected,
                    null);
        }
    }

    static String hash(CompoundTag tag) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateTag(digest, tag);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static void writeIntegrity(CompoundTag tag, String field) {
        tag.putString(field, hash(tag));
    }

    static void verifyIntegrity(
            CompoundTag tag, String field, String subject) {
        requireType(tag, field, Tag.TAG_STRING, subject);
        String expected = tag.getString(field);
        try {
            requireSha256(expected, field);
        } catch (IllegalArgumentException exception) {
            throw invalid(subject, field + " is malformed", exception);
        }
        CompoundTag withoutIntegrity = tag.copy();
        withoutIntegrity.remove(field);
        String actual = hash(withoutIntegrity);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                actual.getBytes(StandardCharsets.US_ASCII))) {
            throw invalid(subject, field + " does not match its contents", null);
        }
    }

    static IllegalStateException invalid(
            String subject, String message, Throwable cause) {
        String fullMessage = "Invalid " + subject + ": " + message;
        return cause == null
                ? new IllegalStateException(fullMessage)
                : new IllegalStateException(fullMessage, cause);
    }

    private static void updateTag(MessageDigest digest, Tag tag) {
        digest.update(tag.getId());
        if (tag instanceof CompoundTag compound) {
            List<String> keys = new ArrayList<>(compound.getAllKeys());
            keys.sort(String::compareTo);
            updateInt(digest, keys.size());
            for (String key : keys) {
                updateString(digest, key);
                updateTag(
                        digest,
                        Objects.requireNonNull(
                                compound.get(key), "compound value"));
            }
            return;
        }
        if (tag instanceof net.minecraft.nbt.ListTag list) {
            updateInt(digest, list.size());
            for (Tag element : list) {
                updateTag(digest, element);
            }
            return;
        }
        updateString(digest, tag.toString());
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }
}
