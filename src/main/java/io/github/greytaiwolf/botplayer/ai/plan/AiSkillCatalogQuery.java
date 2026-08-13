package io.github.greytaiwolf.botplayer.ai.plan;

import java.util.Objects;

/**
 * Trusted, bounded query passed from the server planning session to the P5 skill catalog adapter.
 *
 * <p>The query carries the binding rather than accepting model-provided bot, revision or world facts.
 */
public record AiSkillCatalogQuery(AiPlanBinding binding, int maximumSkills) {
    public static final int MAXIMUM_SKILLS = 64;

    public AiSkillCatalogQuery {
        Objects.requireNonNull(binding, "binding");
        if (maximumSkills < 1 || maximumSkills > MAXIMUM_SKILLS) {
            throw new IllegalArgumentException(
                    "maximumSkills must be between 1 and " + MAXIMUM_SKILLS);
        }
    }

    @Override
    public String toString() {
        return "AiSkillCatalogQuery[maximumSkills=" + maximumSkills
                + ", generation=" + binding.botGeneration()
                + ", revision=" + binding.revision()
                + ", snapshotId=" + binding.observationSnapshotId() + "]";
    }
}
