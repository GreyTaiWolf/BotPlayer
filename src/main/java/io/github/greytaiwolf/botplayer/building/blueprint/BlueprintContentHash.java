package io.github.greytaiwolf.botplayer.building.blueprint;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** A strict, lower-case SHA-256 digest of canonical blueprint content. */
public record BlueprintContentHash(String value)
        implements Comparable<BlueprintContentHash> {
    public static final int HEX_LENGTH = 64;

    private static final String HASH_DOMAIN = "botplayer.blueprint.content.v1";
    private static final Pattern SHA_256 = Pattern.compile("[0-9a-f]{64}");

    public BlueprintContentHash {
        Objects.requireNonNull(value, "value");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "blueprint content hash must be a lower-case SHA-256 hex value");
        }
    }

    static BlueprintContentHash fromCanonicalCells(
            int schemaVersion, List<BlueprintCell> canonicalCells) {
        if (schemaVersion != Blueprint.CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported blueprint schema version");
        }
        Objects.requireNonNull(canonicalCells, "canonicalCells");
        MessageDigest digest = newDigest();
        updateString(digest, HASH_DOMAIN);
        updateInt(digest, schemaVersion);
        updateInt(digest, canonicalCells.size());
        for (BlueprintCell cell : canonicalCells) {
            BlueprintCell nonNullCell = Objects.requireNonNull(cell,
                    "blueprint cell");
            updateInt(digest, nonNullCell.offset().x());
            updateInt(digest, nonNullCell.offset().y());
            updateInt(digest, nonNullCell.offset().z());
            updateString(digest,
                    nonNullCell.expectedState().blockId().value());
            Map<String, String> properties = new TreeMap<>(
                    nonNullCell.expectedState().properties());
            updateInt(digest, properties.size());
            for (Map.Entry<String, String> property : properties.entrySet()) {
                updateString(digest, property.getKey());
                updateString(digest, property.getValue());
            }
            updateString(digest, nonNullCell.role().name());
            updateString(digest, nonNullCell.replacePolicy().name());
            updateString(digest, nonNullCell.materialClass().name());
        }
        return new BlueprintContentHash(HexFormat.of().formatHex(digest.digest()));
    }

    @Override
    public int compareTo(BlueprintContentHash other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "the Java runtime must provide SHA-256", exception);
        }
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "value")
                .getBytes(StandardCharsets.UTF_8);
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
