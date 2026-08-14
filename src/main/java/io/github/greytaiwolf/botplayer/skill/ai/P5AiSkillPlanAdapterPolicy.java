package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * Server-owned limits for the P5 implementation of the AI plan port.
 *
 * <p>The firewall policy is re-applied inside the adapter even when the outer proposal gate has
 * already checked it.  This makes the P5 execution boundary independently fail closed if a caller
 * accidentally bypasses or later refactors the transport gate.
 */
public record P5AiSkillPlanAdapterPolicy(
        ToolFirewallPolicy firewallPolicy,
        int maximumToolCalls,
        SkillRiskLevel maximumSkillRisk,
        Set<SkillCategory> allowedCategories,
        int maximumIssuedTokens) {
    public static final int MAXIMUM_ISSUED_TOKENS = 512;

    public P5AiSkillPlanAdapterPolicy {
        firewallPolicy = Objects.requireNonNull(firewallPolicy, "firewallPolicy");
        if (maximumToolCalls < 1
                || maximumToolCalls > ProposedSkillPlan.MAX_TOOL_CALLS
                || maximumToolCalls > firewallPolicy.maximumCalls()) {
            throw new IllegalArgumentException(
                    "maximumToolCalls must be inside the firewall call limit");
        }
        maximumSkillRisk = Objects.requireNonNull(maximumSkillRisk, "maximumSkillRisk");
        Objects.requireNonNull(allowedCategories, "allowedCategories");
        if (allowedCategories.isEmpty()) {
            throw new IllegalArgumentException("allowedCategories must not be empty");
        }
        EnumSet<SkillCategory> copiedCategories = EnumSet.noneOf(SkillCategory.class);
        for (SkillCategory category : allowedCategories) {
            copiedCategories.add(Objects.requireNonNull(category, "allowed category"));
        }
        allowedCategories = Collections.unmodifiableSet(copiedCategories);
        if (maximumIssuedTokens < 1 || maximumIssuedTokens > MAXIMUM_ISSUED_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumIssuedTokens must be between 1 and " + MAXIMUM_ISSUED_TOKENS);
        }
    }
}
