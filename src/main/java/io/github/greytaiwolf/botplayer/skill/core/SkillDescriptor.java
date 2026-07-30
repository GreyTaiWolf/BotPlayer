package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * 技能注册、计划校验和运行预算共享的不可变描述。
 */
public record SkillDescriptor(
        SkillId id,
        SkillVersion version,
        SkillCategory category,
        SkillParameterSchema parameterSchema,
        SkillRiskLevel risk,
        Set<SkillId> requiredCapabilities,
        int maximumRunTicks,
        int maximumRetries,
        boolean resumable) {
    public static final int MAX_CAPABILITIES = 64;
    public static final int MAX_RUN_TICKS = 1_728_000;
    public static final int MAX_RETRIES = 16;

    public SkillDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(parameterSchema, "parameterSchema");
        Objects.requireNonNull(risk, "risk");
        Objects.requireNonNull(
                requiredCapabilities, "requiredCapabilities");
        if (requiredCapabilities.size() > MAX_CAPABILITIES) {
            throw new IllegalArgumentException(
                    "requiredCapabilities exceeds maximum size "
                            + MAX_CAPABILITIES);
        }
        Set<SkillId> sorted = new TreeSet<>();
        requiredCapabilities.forEach(capability ->
                sorted.add(Objects.requireNonNull(
                        capability, "required capability")));
        requiredCapabilities = Collections.unmodifiableSet(
                new LinkedHashSet<>(sorted));
        if (maximumRunTicks < 1
                || maximumRunTicks > MAX_RUN_TICKS) {
            throw new IllegalArgumentException(
                    "maximumRunTicks must be between 1 and "
                            + MAX_RUN_TICKS);
        }
        if (maximumRetries < 0
                || maximumRetries > MAX_RETRIES) {
            throw new IllegalArgumentException(
                    "maximumRetries must be between 0 and "
                            + MAX_RETRIES);
        }
    }
}
