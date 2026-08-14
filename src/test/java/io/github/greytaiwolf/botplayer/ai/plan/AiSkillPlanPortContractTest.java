package io.github.greytaiwolf.botplayer.ai.plan;

import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedToolCall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class AiSkillPlanPortContractTest {
    @Test
    void bindingIsServerShapedBoundedAndMatchesOnlyItsOwnProposal() {
        AiPlanBinding binding = binding();
        ProposedSkillPlan matching = proposal(binding.requestId());

        Assertions.assertTrue(binding.matches(matching));
        Assertions.assertFalse(binding.matches(proposal(UUID.randomUUID())));
        Assertions.assertFalse(binding.isExpiredAt(binding.expiresAtTick() - 1));
        Assertions.assertTrue(binding.isExpiredAt(binding.expiresAtTick()));
        Assertions.assertFalse(binding.toString().contains(binding.ownerId().toString()));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiPlanBinding(
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), 1L, 1L, UUID.randomUUID(), 1L,
                        1L, 1L + AiPlanBinding.MAX_LIFETIME_TICKS + 1L));
    }

    @Test
    void catalogDescriptorTiesStaticToolSchemaToOneSkillWithoutLoggingSummary() {
        ToolDefinition definition = new ToolDefinition(
                "collect_resource",
                ToolRiskLevel.LOW,
                Map.of("itemId", ToolParameterRule.requiredResourceIdentifier(64)));
        AiVisibleSkillDescriptor descriptor = new AiVisibleSkillDescriptor(
                "collect_resource",
                new SkillId("botplayer", "collect_resource"),
                new SkillVersion(1, 0, 0),
                SkillCategory.RESOURCE,
                SkillRiskLevel.LOW,
                definition,
                "Collects only a reviewed resource using the current server policy.");

        Assertions.assertEquals("collect_resource", descriptor.toolName());
        Assertions.assertFalse(descriptor.toString().contains(descriptor.summary()));
        AiSkillCatalog catalog = new AiSkillCatalog(
                new AiSkillCatalogQuery(binding(), 1), List.of(descriptor));
        Assertions.assertEquals(1, catalog.skills().size());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiSkillCatalog(
                        new AiSkillCatalogQuery(binding(), 1),
                        List.of(descriptor, descriptor)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiSkillCatalog(
                        new AiSkillCatalogQuery(binding(), 2),
                        List.of(descriptor, descriptor)));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiVisibleSkillDescriptor(
                        "other_tool", descriptor.skillId(), descriptor.skillVersion(),
                        descriptor.category(), descriptor.risk(), definition,
                        descriptor.summary()));
    }

    @Test
    void validationTokenCannotCrossRequestAndFailuresCannotCarryOne() {
        AiPlanBinding binding = binding();
        ProposedSkillPlan proposal = proposal(binding.requestId());
        AiValidatedSkillPlan token = new TestValidatedPlan(
                UUID.randomUUID(), binding, 1, 2, 1);
        AiPlanValidation accepted = AiPlanValidation.accepted(
                binding, proposal, token);

        Assertions.assertEquals(AiPlanValidationStatus.ACCEPTED, accepted.status());
        Assertions.assertTrue(accepted.validatedPlan().isPresent());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiPlanValidation.accepted(
                        binding, proposal(UUID.randomUUID()), token));
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AiPlanValidation(
                        AiPlanValidationStatus.PLAN_INVALID,
                        java.util.Optional.of(token)));
        Assertions.assertFalse(accepted.toString().contains(proposal.toolCalls().get(0).callId()));
    }

    @Test
    void submissionOnlyCarriesRunIdentityForSubmittedStatus() {
        AiPlanSubmission submitted = AiPlanSubmission.submitted(UUID.randomUUID());
        AiPlanSubmission rejected = AiPlanSubmission.rejected(
                AiPlanSubmissionStatus.BINDING_STALE);

        Assertions.assertTrue(submitted.skillRunId().isPresent());
        Assertions.assertTrue(rejected.skillRunId().isEmpty());
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> AiPlanSubmission.rejected(AiPlanSubmissionStatus.SUBMITTED));
        Assertions.assertFalse(submitted.toString().contains(
                submitted.skillRunId().orElseThrow().toString()));
    }

    private static AiPlanBinding binding() {
        return new AiPlanBinding(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 7L, 11L, UUID.randomUUID(), 19L,
                100L, 400L);
    }

    private static ProposedSkillPlan proposal(UUID requestId) {
        return new ProposedSkillPlan(requestId, List.of(new ProposedToolCall(
                "call-1", "collect_resource", Map.of())));
    }

    private record TestValidatedPlan(
            UUID validationId,
            AiPlanBinding binding,
            int sourceToolCallCount,
            int nodeCount,
            int edgeCount) implements AiValidatedSkillPlan {}
}
