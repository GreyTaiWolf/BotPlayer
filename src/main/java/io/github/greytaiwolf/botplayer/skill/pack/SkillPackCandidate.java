package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Objects;

/**
 * 解析结果和原始有限字节的绑定；哈希始终由实际字节重新计算。
 */
public record SkillPackCandidate(
        SkillPackDefinition definition, SkillPackSource source) {
    public SkillPackCandidate {
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(source, "source");
    }

    public SkillPackRevision revision() {
        return new SkillPackRevision(
                definition.id(),
                definition.version(),
                source.contentHash());
    }
}
