package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.ai.plan.AiPlanBinding;

/**
 * P5-owned live-fact recheck for an AI plan binding.
 *
 * <p>The implementation belongs at the lifecycle/observation boundary and must derive every fact
 * from the server: persistent owner, active agent, body generation, revision, snapshot and world
 * freshness.  The P6 port deliberately sees only this boolean result, never Minecraft objects or a
 * way to alter authority.
 */
@FunctionalInterface
public interface P5AiPlanBindingAuthority {
    /**
     * Returns true only while the whole server-issued binding is still current at {@code currentTick}.
     */
    boolean isCurrent(AiPlanBinding binding, long currentTick);
}
