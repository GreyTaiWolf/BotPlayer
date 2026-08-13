package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.Objects;

/**
 * 批准、启用和恢复都必须使用这组三元组，不能只按名称匹配包。
 */
public record SkillPackRevision(
        SkillPackId id, SkillVersion version, SkillPackHash contentHash)
        implements Comparable<SkillPackRevision> {
    public SkillPackRevision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(contentHash, "contentHash");
    }

    @Override
    public int compareTo(SkillPackRevision other) {
        Objects.requireNonNull(other, "other");
        int idOrder = id.compareTo(other.id);
        if (idOrder != 0) {
            return idOrder;
        }
        int versionOrder = version.compareTo(other.version);
        return versionOrder != 0
                ? versionOrder
                : contentHash.compareTo(other.contentHash);
    }
}
