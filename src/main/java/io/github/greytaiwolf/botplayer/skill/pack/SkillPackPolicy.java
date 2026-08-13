package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 仅由服务器配置建立的 schema 和 descriptor 精确白名单。
 */
public record SkillPackPolicy(
        SkillPackLimits limits,
        Set<Integer> supportedSchemaVersions,
        Set<SkillPackDescriptorReference> allowedDescriptors) {
    public static final int MAX_SUPPORTED_SCHEMA_VERSIONS = 16;
    public static final int MAX_ALLOWLISTED_DESCRIPTORS = 4_096;

    public SkillPackPolicy {
        Objects.requireNonNull(limits, "limits");
        Objects.requireNonNull(
                supportedSchemaVersions, "supportedSchemaVersions");
        if (supportedSchemaVersions.isEmpty()
                || supportedSchemaVersions.size()
                        > MAX_SUPPORTED_SCHEMA_VERSIONS) {
            throw new IllegalArgumentException(
                    "supportedSchemaVersions must contain 1-"
                            + MAX_SUPPORTED_SCHEMA_VERSIONS
                            + " values");
        }
        Set<Integer> schemas = new TreeSet<>();
        supportedSchemaVersions.forEach(version -> {
            int value = Objects.requireNonNull(
                    version, "schema version");
            if (value < SkillPackDefinition.MIN_SCHEMA_VERSION
                    || value > SkillPackDefinition.MAX_SCHEMA_VERSION) {
                throw new IllegalArgumentException(
                        "unsupported schema version outside absolute bounds");
            }
            schemas.add(value);
        });
        supportedSchemaVersions = Collections.unmodifiableSet(
                new LinkedHashSet<>(schemas));

        Objects.requireNonNull(
                allowedDescriptors, "allowedDescriptors");
        if (allowedDescriptors.size()
                > MAX_ALLOWLISTED_DESCRIPTORS) {
            throw new IllegalArgumentException(
                    "allowedDescriptors exceeds maximum "
                            + MAX_ALLOWLISTED_DESCRIPTORS);
        }
        Set<SkillPackDescriptorReference> descriptors =
                new TreeSet<>();
        allowedDescriptors.forEach(reference -> descriptors.add(
                Objects.requireNonNull(
                        reference, "allowed descriptor")));
        allowedDescriptors = Collections.unmodifiableSet(
                new LinkedHashSet<>(descriptors));
    }

    public boolean allows(SkillPackDescriptorReference reference) {
        return allowedDescriptors.contains(
                Objects.requireNonNull(reference, "reference"));
    }

    public boolean supportsSchema(int version) {
        return supportedSchemaVersions.contains(version);
    }
}
