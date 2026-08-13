package io.github.greytaiwolf.botplayer.ai.plan;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;

/**
 * The only P6-to-P5 plan boundary.
 *
 * <p>Callers first pass a syntax-checked, static-Firewall-approved proposal together with a
 * server-derived binding. The implementation maps only catalog-visible tools, validates the canonical
 * P5 plan, and returns an opaque token. Submission must accept only tokens issued by that same port and
 * must recheck current owner, active agent, generation, revision, snapshot/world facts, deadline and P5
 * capacity before it delegates to the runtime. Neither method grants P6 direct access to SkillRuntime,
 * actions, techniques or Minecraft objects.
 */
public interface AiSkillPlanPort {
    AiPlanValidation validate(
            AiPlanBinding binding, ProposedSkillPlan proposal);

    AiPlanSubmission submit(AiValidatedSkillPlan validatedPlan);
}
