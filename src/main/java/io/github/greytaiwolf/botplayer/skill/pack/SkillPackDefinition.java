package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 解析后的声明式正文；它只含白名单 descriptor 引用和已有的纯数据 DAG。
 */
public record SkillPackDefinition(
        int schemaVersion,
        SkillPackId id,
        io.github.greytaiwolf.botplayer.skill.core.SkillVersion version,
        Set<SkillPackDescriptorReference> descriptorReferences,
        SkillPlan plan) {
    public static final int MIN_SCHEMA_VERSION = 1;
    public static final int MAX_SCHEMA_VERSION = 16;
    public static final int ABSOLUTE_MAX_DESCRIPTOR_REFERENCES = 512;

    public SkillPackDefinition {
        if (schemaVersion < MIN_SCHEMA_VERSION
                || schemaVersion > MAX_SCHEMA_VERSION) {
            throw new IllegalArgumentException(
                    "schemaVersion must be between "
                            + MIN_SCHEMA_VERSION
                            + " and "
                            + MAX_SCHEMA_VERSION);
        }
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(
                descriptorReferences, "descriptorReferences");
        if (descriptorReferences.size()
                > ABSOLUTE_MAX_DESCRIPTOR_REFERENCES) {
            throw new IllegalArgumentException(
                    "descriptorReferences exceeds absolute maximum "
                            + ABSOLUTE_MAX_DESCRIPTOR_REFERENCES);
        }
        Set<SkillPackDescriptorReference> sorted = new TreeSet<>();
        descriptorReferences.forEach(reference -> sorted.add(
                Objects.requireNonNull(
                        reference, "descriptor reference")));
        descriptorReferences = Collections.unmodifiableSet(
                new LinkedHashSet<>(sorted));
        Objects.requireNonNull(plan, "plan");
    }
}
