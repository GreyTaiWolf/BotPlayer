package io.github.greytaiwolf.botplayer.ai.plan;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** A query-bound, immutable catalog result that cannot exceed its server-issued budget. */
public record AiSkillCatalog(
        AiSkillCatalogQuery query, List<AiVisibleSkillDescriptor> skills) {
    public AiSkillCatalog {
        query = Objects.requireNonNull(query, "query");
        Objects.requireNonNull(skills, "skills");
        if (skills.size() > query.maximumSkills()) {
            throw new IllegalArgumentException(
                    "catalog exceeds the server-issued maximumSkills budget");
        }
        List<AiVisibleSkillDescriptor> copied = new ArrayList<>(skills.size());
        Set<String> toolNames = new HashSet<>();
        for (AiVisibleSkillDescriptor descriptor : skills) {
            AiVisibleSkillDescriptor value = Objects.requireNonNull(
                    descriptor, "skill descriptor");
            if (!toolNames.add(value.toolName())) {
                throw new IllegalArgumentException(
                        "catalog contains duplicate visible tool names");
            }
            copied.add(value);
        }
        skills = List.copyOf(copied);
    }

    @Override
    public String toString() {
        return "AiSkillCatalog[skillCount=" + skills.size()
                + ", maximumSkills=" + query.maximumSkills()
                + ", generation=" + query.binding().botGeneration()
                + ", revision=" + query.binding().revision() + "]";
    }
}
