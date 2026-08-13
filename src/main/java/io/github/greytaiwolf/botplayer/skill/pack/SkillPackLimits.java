package io.github.greytaiwolf.botplayer.skill.pack;

import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import java.util.Objects;

/**
 * 服务器为外部声明内容设置的可配置上限；绝对上限仍在 DTO 构造器中生效。
 */
public record SkillPackLimits(
        int maximumSourceBytes,
        int maximumDescriptorReferences,
        int maximumTrackedPacks,
        SkillPlanLimits planLimits,
        SkillRiskLevel maximumRisk) {
    public static final int MAX_TRACKED_PACKS = 512;

    public SkillPackLimits {
        requireRange(
                maximumSourceBytes,
                1,
                SkillPackSource.ABSOLUTE_MAX_BYTES,
                "maximumSourceBytes");
        requireRange(
                maximumDescriptorReferences,
                1,
                SkillPackDefinition.ABSOLUTE_MAX_DESCRIPTOR_REFERENCES,
                "maximumDescriptorReferences");
        requireRange(
                maximumTrackedPacks,
                1,
                MAX_TRACKED_PACKS,
                "maximumTrackedPacks");
        Objects.requireNonNull(planLimits, "planLimits");
        Objects.requireNonNull(maximumRisk, "maximumRisk");
    }

    public static SkillPackLimits defaults() {
        return new SkillPackLimits(
                262_144,
                128,
                128,
                SkillPlanLimits.defaults(),
                SkillRiskLevel.MODERATE);
    }

    private static void requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name
                            + " must be between "
                            + minimum
                            + " and "
                            + maximum);
        }
    }
}
