package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.Objects;
import java.util.UUID;

/**
 * 提交给通用运行时的不可变计划。计划归属 bot 必须与请求身份完全一致。
 */
public record SkillRunRequest(
        UUID botId,
        long botGeneration,
        SkillPlan plan,
        long submittedTick) {
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public SkillRunRequest {
        Objects.requireNonNull(botId, "botId");
        if (ZERO_UUID.equals(botId)) {
            throw new IllegalArgumentException(
                    "botId must not be the zero UUID");
        }
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
        plan = Objects.requireNonNull(plan, "plan");
        if (!botId.equals(plan.botId())) {
            throw new IllegalArgumentException(
                    "plan botId must equal request botId");
        }
        if (submittedTick < 0L) {
            throw new IllegalArgumentException(
                    "submittedTick must not be negative");
        }
    }
}
