package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 已读取但尚未信任的包字节。路径和字节数先受绝对上限约束，避免审核层持有任意文件。
 */
public record SkillPackSource(String relativePath, byte[] bytes) {
    public static final int MAX_PATH_LENGTH = 256;
    public static final int ABSOLUTE_MAX_BYTES = 1_048_576;

    private static final Pattern SAFE_PATH = Pattern.compile(
            "data/[a-z0-9_.-]{1,64}/botplayer/skills/[a-z0-9_-]{1,64}\\.json");

    public SkillPackSource {
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(bytes, "bytes");
        if (relativePath.length() > MAX_PATH_LENGTH
                || !SAFE_PATH.matcher(relativePath).matches()) {
            throw new IllegalArgumentException(
                    "relativePath must be a safe skill-pack data path");
        }
        if (bytes.length > ABSOLUTE_MAX_BYTES) {
            throw new IllegalArgumentException(
                    "source bytes exceeds absolute maximum "
                            + ABSOLUTE_MAX_BYTES);
        }
        bytes = bytes.clone();
    }

    @Override
    public byte[] bytes() {
        return bytes.clone();
    }

    public int byteCount() {
        return bytes.length;
    }

    public SkillPackHash contentHash() {
        return SkillPackHash.sha256(bytes);
    }

    public boolean matches(SkillPackId id) {
        return relativePath.equals(
                Objects.requireNonNull(id, "id").expectedSourcePath());
    }
}
