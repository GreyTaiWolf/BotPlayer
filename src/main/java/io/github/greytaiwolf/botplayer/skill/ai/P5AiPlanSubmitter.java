package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.ai.plan.AiPlanBinding;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmission;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;

/**
 * P5-owned final handoff from an already validated AI plan token to a deterministic runtime.
 *
 * <p>This is intentionally an injected narrow port.  It keeps {@code ai/**} unable to call a
 * {@code SkillRuntime}, inspect a Minecraft object, or turn a model response into an action.  A
 * lifecycle implementation must still run on the server thread and may reject for capacity or any
 * contemporaneous P5 precondition.
 */
@FunctionalInterface
public interface P5AiPlanSubmitter {
    AiPlanSubmission submit(
            AiPlanBinding binding, SkillPlan plan, long currentTick);
}
