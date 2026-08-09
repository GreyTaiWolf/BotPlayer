package io.github.greytaiwolf.botplayer.skill.pack;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 绑定批准记录的 SHA-256 内容摘要，不接受大小写或截断形式。
 */
public record SkillPackHash(String value)
        implements Comparable<SkillPackHash> {
    public static final int HEX_LENGTH = 64;

    private static final Pattern SHA_256 =
            Pattern.compile("[0-9a-f]{64}");

    public SkillPackHash {
        Objects.requireNonNull(value, "value");
        if (!SHA_256.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "content hash must be a lower-case SHA-256 hex value");
        }
    }

    public static SkillPackHash sha256(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            return new SkillPackHash(HexFormat.of().formatHex(
                    digest.digest(bytes)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "the Java runtime must provide SHA-256", exception);
        }
    }

    @Override
    public int compareTo(SkillPackHash other) {
        return value.compareTo(Objects.requireNonNull(other, "other").value);
    }
}
