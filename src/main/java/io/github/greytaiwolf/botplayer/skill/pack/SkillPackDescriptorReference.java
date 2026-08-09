package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.Objects;

/**
 * 声明式包只能引用注册表中的精确 descriptor 版本。
 */
public record SkillPackDescriptorReference(
        SkillId skillId, SkillVersion skillVersion)
        implements Comparable<SkillPackDescriptorReference> {
    public SkillPackDescriptorReference {
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(skillVersion, "skillVersion");
    }

    @Override
    public int compareTo(SkillPackDescriptorReference other) {
        Objects.requireNonNull(other, "other");
        int idOrder = skillId.compareTo(other.skillId);
        return idOrder != 0
                ? idOrder
                : skillVersion.compareTo(other.skillVersion);
    }
}
