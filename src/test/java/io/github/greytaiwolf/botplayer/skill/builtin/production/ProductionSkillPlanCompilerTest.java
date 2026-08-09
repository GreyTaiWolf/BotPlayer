package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionSkillPlanCompilerTest {
    private static final UUID FIRST_BOT = new UUID(4L, 1L);
    private static final UUID SECOND_BOT = new UUID(4L, 2L);

    @Test
    void lowersValidatedWoodToIronDagIntoOneReviewedContractPerPhysicalAction() {
        ProductionSkillPlanCompiler compiler =
                ProductionSkillPlanCompiler.p5aDefault();
        SkillPlan first = compiler.compileWoodToIronPick(FIRST_BOT, 1L);
        SkillPlan sameTemplateDifferentRunContext =
                compiler.compileWoodToIronPick(SECOND_BOT, 99L);
        ProductionPlanTemplate template = WoodToIronPickTemplate.create();

        assertEquals(12, template.nodes().size(),
                "模板仍是 12 个逻辑生产节点");
        assertEquals(31, first.nodes().size(),
                "每次采集和每个 crafting batch 必须成为独立物理原版动作");
        assertEquals(36, first.edges().size(),
                "17 条逻辑边加 19 条 fragment 串行边");
        assertEquals(first.planId(), sameTemplateDifferentRunContext.planId());
        assertNotEquals(first.botId(), sameTemplateDifferentRunContext.botId());
        assertNotEquals(first.revision(), sameTemplateDifferentRunContext.revision());

        Map<String, SkillPlanNode> byOperationId = new LinkedHashMap<>();
        for (SkillPlanNode node : first.nodes()) {
            assertEquals(P5ABuiltinSkillIds.BOOTSTRAP_IRON, node.skillId());
            assertEquals(P5ABuiltinSkillIds.VERSION, node.skillVersion());
            assertEquals(
                    1,
                    node.parameters().values().size(),
                    "compiler must not smuggle coordinates, commands, sessions, or run ids into parameters");
            Object operationId = node.parameters().values().get(
                    P5ABuiltinSkillIds
                            .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER);
            assertTrue(operationId instanceof String);
            byOperationId.put((String) operationId, node);
        }

        assertEquals(31, byOperationId.size());
        assertEquals(31, ProductionSkillPlanCompiler.approvedOperationIds()
                .size());
        Map<String, SkillPlanNode> secondByOperationId = new LinkedHashMap<>();
        for (SkillPlanNode node : sameTemplateDifferentRunContext.nodes()) {
            secondByOperationId.put((String) node.parameters().values().get(
                    P5ABuiltinSkillIds
                            .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER), node);
        }
        for (ProductionPlanNode source : template.nodes()) {
            List<String> fragmentIds = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.nodeId());
            assertEquals(expectedPhysicalSteps(source), fragmentIds.size(),
                    () -> "unexpected physical fragment count for "
                            + source.nodeId());
            assertEquals(fragmentIds.get(0), ProductionSkillPlanCompiler
                    .operationIdForProductionNode(source.nodeId())
                    .orElseThrow());
            for (int index = 0; index < fragmentIds.size(); index++) {
                String operationId = fragmentIds.get(index);
                SkillPlanNode compiled = byOperationId.get(operationId);
                assertTrue(compiled != null,
                        () -> "missing physical node for " + operationId);
                assertEquals(
                        Map.of(
                                P5ABuiltinSkillIds
                                        .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                                operationId),
                        compiled.parameters().values());
                ProductionResolvedNode resolved = ProductionSkillPlanCompiler
                        .approvedOperation(operationId)
                        .orElseThrow();
                assertEquals(source.nodeId() + ".fragment-" + (index + 1)
                                + "-of-" + fragmentIds.size(),
                        resolved.node().nodeId());
                assertEquals(source.checkpoint()
                                && index + 1 == fragmentIds.size(),
                        resolved.node().checkpoint(),
                        "checkpoint may occur only after a logical node's final action");
                assertEquals(
                        compiled.nodeId(),
                        secondByOperationId.get(operationId).nodeId(),
                        "node UUID must be derived from reviewed fragment semantics, not run context");
                assertSinglePhysicalOperation(source, resolved);
            }
        }

        List<SkillPlanEdge> expectedEdges = new ArrayList<>();
        for (ProductionPlanNode source : template.nodes()) {
            List<String> fragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.nodeId());
            for (int index = 1; index < fragments.size(); index++) {
                expectedEdges.add(new SkillPlanEdge(
                        byOperationId.get(fragments.get(index - 1)).nodeId(),
                        byOperationId.get(fragments.get(index)).nodeId()));
            }
        }
        for (ProductionPlanEdge source : template.edges()) {
            List<String> beforeFragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.beforeNodeId());
            List<String> afterFragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.afterNodeId());
            expectedEdges.add(new SkillPlanEdge(
                    byOperationId.get(beforeFragments.get(
                            beforeFragments.size() - 1)).nodeId(),
                    byOperationId.get(afterFragments.get(0)).nodeId()));
        }
        assertEquals(expectedEdges, first.edges());
        assertEquals(first.edges(), sameTemplateDifferentRunContext.edges());

        SkillRegistry registry = new SkillRegistry();
        assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(ProductionSkillPlanCompiler.handlerDescriptor()));
        assertTrue(new SkillPlanValidator(
                registry,
                new SkillPlanLimits(64, 64, 64))
                .validate(first)
                .valid());
    }

    private static int expectedPhysicalSteps(ProductionPlanNode node) {
        ProductionOperation operation = node.operation();
        if (operation instanceof ResourceAcquisition acquisition) {
            return acquisition.expectedGain().quantities().values()
                    .iterator().next();
        }
        if (operation instanceof RecipeExecution recipe) {
            return recipe.batches();
        }
        return 1;
    }

    private static void assertSinglePhysicalOperation(
            ProductionPlanNode source,
            ProductionResolvedNode resolved) {
        ProductionOperation sourceOperation = source.operation();
        ProductionOperation physicalOperation = resolved.node().operation();
        if (sourceOperation instanceof ResourceAcquisition acquisition) {
            ResourceAcquisition physical = (ResourceAcquisition) physicalOperation;
            assertEquals(acquisition.method(), physical.method());
            assertEquals(1, physical.expectedGain().quantities().size());
            assertEquals(1, physical.expectedGain().quantities().values()
                    .iterator().next());
        } else if (sourceOperation instanceof RecipeExecution recipe) {
            RecipeExecution physical = (RecipeExecution) physicalOperation;
            assertEquals(recipe.recipeId(), physical.recipeId());
            assertEquals(1, physical.batches());
        } else {
            assertEquals(sourceOperation, physicalOperation);
        }
    }

    @Test
    void descriptorAllowsOnlyTheReviewedOperationIdParameter() {
        SkillDescriptor descriptor =
                ProductionSkillPlanCompiler.handlerDescriptor();
        String approved = ProductionSkillPlanCompiler.approvedOperationIds()
                .stream()
                .findFirst()
                .orElseThrow();

        assertTrue(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                        approved))).valid());
        assertFalse(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                        "p5a.wood_to_iron_pick.v1.unreviewed"))).valid());
        assertFalse(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER,
                        approved,
                        "target.x",
                        4))).valid());
    }

    @Test
    void rejectsProductionRejectionAndEvenAcceptedTemplateVariants() {
        ProductionSkillPlanCompiler compiler =
                ProductionSkillPlanCompiler.p5aDefault();
        ProductionPlanTemplate original = WoodToIronPickTemplate.create();

        List<ProductionPlanNode> checkpointVariantNodes = new ArrayList<>(
                original.nodes());
        ProductionPlanNode first = checkpointVariantNodes.get(0);
        checkpointVariantNodes.set(0, new ProductionPlanNode(
                first.nodeId(), first.operation(), !first.checkpoint()));
        ProductionPlanTemplate checkpointVariant = new ProductionPlanTemplate(
                original.schema(),
                original.requestedBudget(),
                checkpointVariantNodes,
                original.edges());
        assertTrue(ProductionPlanValidator.p5aDefault()
                .validate(checkpointVariant)
                .accepted());
        assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(FIRST_BOT, 1L, checkpointVariant));

        List<ProductionPlanNode> rejectedNodes = new ArrayList<>(
                original.nodes());
        rejectedNodes.set(1, new ProductionPlanNode(
                "craft_planks",
                new RecipeExecution(
                        new ProductionRecipeId("unreviewed_recipe"), 1),
                false));
        ProductionPlanTemplate rejected = new ProductionPlanTemplate(
                original.schema(),
                original.requestedBudget(),
                rejectedNodes,
                original.edges());
        assertFalse(ProductionPlanValidator.p5aDefault()
                .validate(rejected)
                .accepted());
        assertThrows(
                IllegalArgumentException.class,
                () -> compiler.compile(FIRST_BOT, 1L, rejected));
    }
}
