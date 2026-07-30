package io.github.greytaiwolf.botplayer.skill.plan;

import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.core.SkillVersion;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class SkillPlanValidatorTest {
    private static final UUID PLAN_ID = new UUID(1L, 1L);
    private static final UUID BOT_ID = new UUID(1L, 2L);
    private static final UUID FIRST = new UUID(0L, 1L);
    private static final UUID SECOND = new UUID(0L, 2L);
    private static final UUID THIRD = new UUID(0L, 3L);
    private static final UUID UNKNOWN = new UUID(0L, 99L);
    private static final SkillId FIND =
            new SkillId("botplayer", "resource/find");
    private static final SkillId COLLECT =
            new SkillId("botplayer", "resource/collect");
    private static final SkillVersion VERSION =
            new SkillVersion(1, 0, 0);

    @Test
    void returnsDeterministicSerialOrderAndLongestDepth() {
        SkillPlan plan = plan(
                List.of(
                        node(FIRST, FIND, Map.of("target", "oak")),
                        node(SECOND, FIND, Map.of("target", "birch")),
                        node(THIRD, COLLECT, Map.of("amount", 4))),
                List.of(
                        new SkillPlanEdge(FIRST, THIRD),
                        new SkillPlanEdge(SECOND, THIRD)));

        SkillPlanValidation result = validator(
                new SkillPlanLimits(8, 8, 4)).validate(plan);

        Assertions.assertTrue(result.valid());
        Assertions.assertEquals(
                List.of(FIRST, SECOND, THIRD),
                result.serialExecutionOrder());
        Assertions.assertEquals(2, result.maximumDepth());
        Assertions.assertTrue(result.violations().isEmpty());
    }

    @Test
    void topologyOrderIgnoresNodeAndEdgeInputOrder() {
        SkillPlan canonical = plan(
                List.of(
                        node(FIRST, FIND, Map.of("target", "oak")),
                        node(SECOND, COLLECT, Map.of("amount", 1)),
                        node(THIRD, COLLECT, Map.of("amount", 2))),
                List.of(
                        new SkillPlanEdge(FIRST, SECOND),
                        new SkillPlanEdge(FIRST, THIRD)));
        SkillPlan reordered = plan(
                List.of(
                        node(THIRD, COLLECT, Map.of("amount", 2)),
                        node(FIRST, FIND, Map.of("target", "oak")),
                        node(SECOND, COLLECT, Map.of("amount", 1))),
                List.of(
                        new SkillPlanEdge(FIRST, THIRD),
                        new SkillPlanEdge(FIRST, SECOND)));
        SkillPlanValidator validator =
                validator(new SkillPlanLimits(8, 8, 4));

        SkillPlanValidation canonicalResult =
                validator.validate(canonical);
        SkillPlanValidation reorderedResult =
                validator.validate(reordered);

        Assertions.assertTrue(canonicalResult.valid());
        Assertions.assertTrue(reorderedResult.valid());
        Assertions.assertEquals(
                List.of(FIRST, SECOND, THIRD),
                canonicalResult.serialExecutionOrder());
        Assertions.assertEquals(
                canonicalResult.serialExecutionOrder(),
                reorderedResult.serialExecutionOrder());
    }

    @Test
    void rejectsUnknownSkillVersionAndInvalidParameters() {
        SkillPlan plan = plan(
                List.of(
                        node(
                                FIRST,
                                new SkillId(
                                        "botplayer", "resource/missing"),
                                Map.of()),
                        new SkillPlanNode(
                                SECOND,
                                FIND,
                                new SkillVersion(9, 0, 0),
                                new SkillParameters(
                                        Map.of("target", "oak"))),
                        node(
                                THIRD,
                                COLLECT,
                                Map.of("amount", 0))),
                List.of());

        SkillPlanValidation result =
                validator(SkillPlanLimits.defaults())
                        .validate(plan);

        Assertions.assertFalse(result.valid());
        Assertions.assertEquals(
                Set.of(
                        SkillPlanViolation.Code.UNKNOWN_SKILL,
                        SkillPlanViolation.Code
                                .SKILL_VERSION_MISMATCH,
                        SkillPlanViolation.Code.INVALID_PARAMETERS),
                result.violations().stream()
                        .map(SkillPlanViolation::code)
                        .collect(java.util.stream.Collectors.toSet()));
        Assertions.assertTrue(
                result.serialExecutionOrder().isEmpty());
    }

    @Test
    void rejectsCyclesAndDepthBeyondLimit() {
        SkillPlan cycle = plan(
                List.of(
                        node(FIRST, FIND, Map.of("target", "oak")),
                        node(SECOND, COLLECT, Map.of("amount", 1))),
                List.of(
                        new SkillPlanEdge(FIRST, SECOND),
                        new SkillPlanEdge(SECOND, FIRST)));
        SkillPlanValidation cycleResult =
                validator(new SkillPlanLimits(8, 8, 8))
                        .validate(cycle);
        Assertions.assertTrue(cycleResult.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillPlanViolation.Code
                                        .CYCLE_DETECTED));

        SkillPlan deep = plan(
                List.of(
                        node(FIRST, FIND, Map.of("target", "oak")),
                        node(SECOND, COLLECT, Map.of("amount", 1)),
                        node(THIRD, COLLECT, Map.of("amount", 2))),
                List.of(
                        new SkillPlanEdge(FIRST, SECOND),
                        new SkillPlanEdge(SECOND, THIRD)));
        SkillPlanValidation depthResult =
                validator(new SkillPlanLimits(8, 8, 2))
                        .validate(deep);
        Assertions.assertEquals(3, depthResult.maximumDepth());
        Assertions.assertTrue(depthResult.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillPlanViolation.Code
                                        .DEPTH_EXCEEDED));
    }

    @Test
    void rejectsDuplicateDanglingAndSelfEdges() {
        SkillPlanNode first =
                node(FIRST, FIND, Map.of("target", "oak"));
        SkillPlanEdge repeated =
                new SkillPlanEdge(FIRST, SECOND);
        SkillPlan plan = plan(
                List.of(
                        first,
                        first,
                        node(SECOND, COLLECT, Map.of("amount", 1))),
                List.of(
                        repeated,
                        repeated,
                        new SkillPlanEdge(SECOND, SECOND),
                        new SkillPlanEdge(SECOND, UNKNOWN)));

        SkillPlanValidation result =
                validator(SkillPlanLimits.defaults())
                        .validate(plan);

        Assertions.assertEquals(
                Set.of(
                        SkillPlanViolation.Code.DUPLICATE_NODE,
                        SkillPlanViolation.Code.DUPLICATE_EDGE,
                        SkillPlanViolation.Code.SELF_EDGE,
                        SkillPlanViolation.Code.UNKNOWN_EDGE_NODE),
                result.violations().stream()
                        .map(SkillPlanViolation::code)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void enforcesConfiguredNodeAndEdgeLimits() {
        SkillPlan plan = plan(
                List.of(
                        node(FIRST, FIND, Map.of("target", "oak")),
                        node(SECOND, COLLECT, Map.of("amount", 1)),
                        node(THIRD, COLLECT, Map.of("amount", 2))),
                List.of(
                        new SkillPlanEdge(FIRST, SECOND),
                        new SkillPlanEdge(FIRST, THIRD)));

        SkillPlanValidation result =
                validator(new SkillPlanLimits(2, 1, 4))
                        .validate(plan);

        Assertions.assertTrue(result.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillPlanViolation.Code
                                        .NODE_LIMIT_EXCEEDED));
        Assertions.assertTrue(result.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillPlanViolation.Code
                                        .EDGE_LIMIT_EXCEEDED));
        Assertions.assertTrue(
                result.serialExecutionOrder().isEmpty());
    }

    @Test
    void rejectsEmptyPlanAndInvalidStructuralBounds() {
        SkillPlanValidation result =
                validator(SkillPlanLimits.defaults()).validate(
                        plan(List.of(), List.of()));
        Assertions.assertTrue(result.violations().stream()
                .anyMatch(value ->
                        value.code()
                                == SkillPlanViolation.Code.EMPTY_PLAN));

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillPlanLimits(0, 1, 1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillPlanLimits(1, -1, 1));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillPlan(
                        PLAN_ID,
                        BOT_ID,
                        0L,
                        List.of(),
                        List.of()));

        SkillPlanNode node =
                node(FIRST, FIND, Map.of("target", "oak"));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillPlan(
                        PLAN_ID,
                        BOT_ID,
                        1L,
                        java.util.Collections.nCopies(
                                SkillPlan.ABSOLUTE_MAX_NODES + 1,
                                node),
                        List.of()));
        SkillPlanEdge edge = new SkillPlanEdge(FIRST, SECOND);
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new SkillPlan(
                        PLAN_ID,
                        BOT_ID,
                        1L,
                        List.of(node),
                        java.util.Collections.nCopies(
                                SkillPlan.ABSOLUTE_MAX_EDGES + 1,
                                edge)));
    }

    private static SkillPlanValidator validator(
            SkillPlanLimits limits) {
        SkillRegistry registry = new SkillRegistry();
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(new SkillDescriptor(
                        FIND,
                        VERSION,
                        SkillCategory.RESOURCE,
                        new SkillParameterSchema(Map.of(
                                "target",
                                new SkillParameterRule.StringRule(
                                        true,
                                        1,
                                        16,
                                        Set.of("oak", "birch")))),
                        SkillRiskLevel.LOW,
                        Set.of(),
                        1_200,
                        2,
                        true)));
        Assertions.assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(new SkillDescriptor(
                        COLLECT,
                        VERSION,
                        SkillCategory.RESOURCE,
                        new SkillParameterSchema(Map.of(
                                "amount",
                                new SkillParameterRule.IntegerRule(
                                        true, 1, 64))),
                        SkillRiskLevel.LOW,
                        Set.of(),
                        600,
                        2,
                        true)));
        return new SkillPlanValidator(registry, limits);
    }

    private static SkillPlan plan(
            List<SkillPlanNode> nodes,
            List<SkillPlanEdge> edges) {
        return new SkillPlan(
                PLAN_ID, BOT_ID, 1L, nodes, edges);
    }

    private static SkillPlanNode node(
            UUID nodeId,
            SkillId skillId,
            Map<String, Object> parameters) {
        return new SkillPlanNode(
                nodeId,
                skillId,
                VERSION,
                new SkillParameters(parameters));
    }
}
