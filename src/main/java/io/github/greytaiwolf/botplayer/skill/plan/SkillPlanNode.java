package io.github.greytaiwolf.botplayer.skill.plan;

import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.Objects;
import java.util.UUID;

/**
 * DAG 节点只引用已注册技能及其不可变参数。
 */
public record SkillPlanNode(
        UUID nodeId,
        SkillId skillId,
        SkillVersion skillVersion,
        SkillParameters parameters) {
    public SkillPlanNode {
        PlanChecks.requireNonZero(nodeId, "nodeId");
        Objects.requireNonNull(skillId, "skillId");
        Objects.requireNonNull(skillVersion, "skillVersion");
        Objects.requireNonNull(parameters, "parameters");
    }
}
