package io.github.greytaiwolf.botplayer.ai.plan;

import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.Objects;

/**
 * A static, model-visible description of one P5 skill/tool mapping.
 *
 * <p>Only a trusted {@link AiSkillCatalogPort} creates this descriptor. It communicates schema and
 * risk, but is neither an execution capability nor a declaration that a skill is currently available
 * in the world.
 */
public record AiVisibleSkillDescriptor(
        String toolName,
        SkillId skillId,
        SkillVersion skillVersion,
        SkillCategory category,
        SkillRiskLevel risk,
        ToolDefinition toolDefinition,
        String summary) {
    public static final int MAX_SUMMARY_LENGTH = 512;

    public AiVisibleSkillDescriptor {
        Objects.requireNonNull(toolName, "toolName");
        skillId = Objects.requireNonNull(skillId, "skillId");
        skillVersion = Objects.requireNonNull(skillVersion, "skillVersion");
        category = Objects.requireNonNull(category, "category");
        risk = Objects.requireNonNull(risk, "risk");
        toolDefinition = Objects.requireNonNull(toolDefinition, "toolDefinition");
        if (!toolName.equals(toolDefinition.toolName())) {
            throw new IllegalArgumentException(
                    "toolName must match the trusted tool definition");
        }
        summary = AiPlanChecks.boundedPlainText(
                summary, "summary", MAX_SUMMARY_LENGTH);
    }

    /** Never render parameter schema or descriptive text through an incidental log line. */
    @Override
    public String toString() {
        return "AiVisibleSkillDescriptor[toolNameLength=" + toolName.length()
                + ", skillId=" + skillId
                + ", skillVersion=" + skillVersion
                + ", category=" + category
                + ", risk=" + risk
                + ", parameterCount=" + toolDefinition.parameters().size()
                + ", summaryLength=" + summary.length() + "]";
    }
}
