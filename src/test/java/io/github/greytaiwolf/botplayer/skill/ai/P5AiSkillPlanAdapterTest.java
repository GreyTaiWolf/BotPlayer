package io.github.greytaiwolf.botplayer.skill.ai;

import io.github.greytaiwolf.botplayer.ai.plan.AiPlanBinding;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmission;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanSubmissionStatus;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanValidation;
import io.github.greytaiwolf.botplayer.ai.plan.AiPlanValidationStatus;
import io.github.greytaiwolf.botplayer.ai.plan.AiSkillCatalog;
import io.github.greytaiwolf.botplayer.ai.plan.AiSkillCatalogQuery;
import io.github.greytaiwolf.botplayer.ai.plan.AiValidatedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedSkillPlan;
import io.github.greytaiwolf.botplayer.ai.tool.ProposedToolCall;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolFirewallPolicy;
import io.github.greytaiwolf.botplayer.ai.tool.ToolRiskLevel;
import io.github.greytaiwolf.botplayer.ai.tool.ToolValue;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class P5AiSkillPlanAdapterTest {
    private static final SkillId COLLECT = new SkillId("botplayer", "collect_resource");
    private static final SkillId HIGH_RISK = new SkillId("botplayer", "demolish_resource");
    private static final SkillVersion VERSION = new SkillVersion(1, 0, 0);

    @Test
    void mapsOnlyTrustedScalarToolsToASerialCanonicalPlanAndConsumesTheToken() {
        Harness harness = harness();
        AiSkillCatalog catalog = harness.adapter.visibleSkills(
                new AiSkillCatalogQuery(harness.binding, 4));
        Assertions.assertEquals(1, catalog.skills().size());
        Assertions.assertEquals("collect_resource", catalog.skills().get(0).toolName());
        Assertions.assertFalse(catalog.toString().contains("oak_log"));

        AiPlanValidation validation = harness.adapter.validate(
                harness.binding,
                proposal(harness.binding.requestId(), "minecraft:oak_log", 2, 3));
        Assertions.assertEquals(AiPlanValidationStatus.ACCEPTED, validation.status());
        AiValidatedSkillPlan token = validation.validatedPlan().orElseThrow();
        Assertions.assertEquals(2, token.nodeCount());
        Assertions.assertEquals(1, token.edgeCount());
        Assertions.assertFalse(token.toString().contains("oak_log"));

        AiPlanSubmission submission = harness.adapter.submit(token);
        Assertions.assertEquals(AiPlanSubmissionStatus.SUBMITTED, submission.status());
        Assertions.assertEquals(1, harness.submittedPlans.size());
        SkillPlan submitted = harness.submittedPlans.get(0);
        Assertions.assertEquals(harness.binding.botId(), submitted.botId());
        Assertions.assertEquals(harness.binding.revision(), submitted.revision());
        Assertions.assertEquals(2, submitted.nodes().size());
        Assertions.assertEquals(1, submitted.edges().size());
        Assertions.assertEquals("minecraft:oak_log",
                submitted.nodes().get(0).parameters().value("itemid").orElseThrow());
        Assertions.assertEquals(2,
                submitted.nodes().get(0).parameters().value("amount").orElseThrow());
        Assertions.assertEquals(3,
                submitted.nodes().get(1).parameters().value("amount").orElseThrow());

        Assertions.assertEquals(AiPlanSubmissionStatus.TOKEN_NOT_ISSUED_BY_PORT,
                harness.adapter.submit(token).status());
        Assertions.assertEquals(AiPlanValidationStatus.REQUEST_ALREADY_CONSUMED,
                harness.adapter.validate(harness.binding,
                        proposal(harness.binding.requestId(), "minecraft:oak_log", 2)).status());
    }

    @Test
    void rejectsUnsafeOrUnmappedInputsBeforeTheyBecomeP5Parameters() {
        Harness harness = harness();
        ProposedSkillPlan unsafe = proposal(
                harness.binding.requestId(), "../server.properties", 1);
        Assertions.assertEquals(AiPlanValidationStatus.POLICY_REJECTED,
                harness.adapter.validate(harness.binding, unsafe).status());

        ProposedSkillPlan unmapped = new ProposedSkillPlan(
                harness.binding.requestId(),
                List.of(new ProposedToolCall(
                        "call-unmapped", "demolish_resource", Map.of())));
        Assertions.assertEquals(AiPlanValidationStatus.TOOL_MAPPING_REJECTED,
                harness.adapter.validate(harness.binding, unmapped).status());
        Assertions.assertTrue(harness.submittedPlans.isEmpty());
    }

    @Test
    void rechecksFreshnessOnCatalogValidationAndFinalSubmission() {
        Harness harness = harness();
        harness.current.set(false);
        Assertions.assertTrue(harness.adapter.visibleSkills(
                new AiSkillCatalogQuery(harness.binding, 4)).skills().isEmpty());
        Assertions.assertEquals(AiPlanValidationStatus.BINDING_STALE,
                harness.adapter.validate(harness.binding,
                        proposal(harness.binding.requestId(), "minecraft:oak_log", 1)).status());

        harness.current.set(true);
        AiValidatedSkillPlan token = harness.adapter.validate(
                harness.binding,
                proposal(harness.binding.requestId(), "minecraft:oak_log", 1))
                .validatedPlan().orElseThrow();
        harness.current.set(false);
        Assertions.assertEquals(AiPlanSubmissionStatus.BINDING_STALE,
                harness.adapter.submit(token).status());
        Assertions.assertTrue(harness.submittedPlans.isEmpty());
    }

    @Test
    void rejectsForeignTokensAndBoundsOutstandingIssuedCapabilities() {
        Harness harness = harness(1);
        AiValidatedSkillPlan issued = harness.adapter.validate(
                harness.binding,
                proposal(harness.binding.requestId(), "minecraft:oak_log", 1))
                .validatedPlan().orElseThrow();
        AiPlanBinding secondBinding = binding(88L, 100L);
        Assertions.assertEquals(AiPlanValidationStatus.PORT_NOT_READY,
                harness.adapter.validate(secondBinding,
                        proposal(secondBinding.requestId(), "minecraft:oak_log", 1)).status());
        Assertions.assertEquals(AiPlanSubmissionStatus.TOKEN_NOT_ISSUED_BY_PORT,
                harness.adapter.submit(new ForeignToken(issued.validationId(), harness.binding)).status());
        Assertions.assertEquals(1, harness.adapter.issuedTokenCount());
    }

    private static Harness harness() {
        return harness(4);
    }

    private static Harness harness(int maximumIssuedTokens) {
        SkillRegistry registry = new SkillRegistry();
        registry.register(new SkillDescriptor(
                COLLECT,
                VERSION,
                SkillCategory.RESOURCE,
                new SkillParameterSchema(Map.of(
                        "itemid", new SkillParameterRule.StringRule(
                                true, 1, 64, Set.of("minecraft:oak_log")),
                        "amount", new SkillParameterRule.IntegerRule(true, 1, 64))),
                SkillRiskLevel.LOW,
                Set.of(),
                100,
                0,
                false));
        registry.register(new SkillDescriptor(
                HIGH_RISK,
                VERSION,
                SkillCategory.RESOURCE,
                SkillParameterSchema.empty(),
                SkillRiskLevel.HIGH,
                Set.of(),
                100,
                0,
                false));

        ToolDefinition collect = new ToolDefinition(
                "collect_resource",
                ToolRiskLevel.LOW,
                Map.of(
                        "itemId", io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule
                                .requiredResourceIdentifier(64),
                        "amount", io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule
                                .requiredInteger(1, 64)));
        ToolDefinition demolish = new ToolDefinition(
                "demolish_resource", ToolRiskLevel.LOW, Map.of());
        ToolFirewallPolicy firewall = new ToolFirewallPolicy(
                Map.of(collect.toolName(), collect, demolish.toolName(), demolish),
                Set.of(collect.toolName(), demolish.toolName()),
                ToolRiskLevel.LOW,
                4);
        P5AiSkillPlanAdapterPolicy policy = new P5AiSkillPlanAdapterPolicy(
                firewall,
                4,
                SkillRiskLevel.LOW,
                Set.of(SkillCategory.RESOURCE),
                maximumIssuedTokens);
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicLong currentTick = new AtomicLong(100L);
        List<SkillPlan> submittedPlans = new ArrayList<>();
        P5AiPlanSubmitter submitter = (binding, plan, tick) -> {
            submittedPlans.add(plan);
            return AiPlanSubmission.submitted(new UUID(7L, submittedPlans.size()));
        };
        P5AiSkillPlanAdapter adapter = new P5AiSkillPlanAdapter(
                registry,
                new SkillPlanValidator(registry, new SkillPlanLimits(8, 8, 8)),
                policy,
                Map.of("collect_resource", new P5AiToolSkillBinding(
                        "collect_resource", COLLECT, VERSION,
                        Map.of("itemId", "itemid"),
                        "Collect an explicitly allowlisted resource.")),
                (binding, tick) -> current.get() && tick == currentTick.get(),
                submitter,
                currentTick::get,
                ids());
        return new Harness(adapter, binding(77L, 100L), current, submittedPlans);
    }

    private static ProposedSkillPlan proposal(
            UUID requestId, String itemId, int... amounts) {
        List<ProposedToolCall> calls = new ArrayList<>();
        for (int index = 0; index < amounts.length; index++) {
            calls.add(new ProposedToolCall(
                    "call-" + index,
                    "collect_resource",
                    Map.of(
                            "itemId", new ToolValue.StringValue(itemId),
                            "amount", new ToolValue.IntegerValue(amounts[index]))));
        }
        return new ProposedSkillPlan(requestId, calls);
    }

    private static AiPlanBinding binding(long identity, long issuedAtTick) {
        return new AiPlanBinding(
                new UUID(1L, identity),
                new UUID(2L, identity),
                new UUID(3L, identity),
                new UUID(4L, identity),
                3L,
                7L,
                new UUID(5L, identity),
                9L,
                issuedAtTick,
                issuedAtTick + 100L);
    }

    private static Supplier<UUID> ids() {
        AtomicLong next = new AtomicLong(1L);
        return () -> new UUID(99L, next.getAndIncrement());
    }

    private record Harness(
            P5AiSkillPlanAdapter adapter,
            AiPlanBinding binding,
            AtomicBoolean current,
            List<SkillPlan> submittedPlans) {}

    private record ForeignToken(UUID validationId, AiPlanBinding binding)
            implements AiValidatedSkillPlan {
        @Override
        public int sourceToolCallCount() {
            return 1;
        }

        @Override
        public int nodeCount() {
            return 1;
        }

        @Override
        public int edgeCount() {
            return 0;
        }
    }
}
