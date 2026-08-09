package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillCategory;
import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterRule;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterSchema;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRiskLevel;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ProductionSkillPlanCompilerTest {
    private static final UUID FIRST_BOT = new UUID(4L, 1L);
    private static final UUID SECOND_BOT = new UUID(4L, 2L);
    private static final Set<ProductionPlanEdge>
            EXACT_MAIN_HAND_REROUTED_EDGES = Set.of(
                    new ProductionPlanEdge(
                            "crafting_table", "place_crafting_table"),
                    new ProductionPlanEdge(
                            "wooden_pickaxe", "mine_cobblestone"),
                    new ProductionPlanEdge("craft_furnace", "place_furnace"),
                    new ProductionPlanEdge(
                            "stone_pickaxe", "mine_raw_iron"),
                    new ProductionPlanEdge(
                            "stone_pickaxe", "mine_coal"));

    @Test
    void lowersValidatedWoodToIronDagIntoPhysicalActionsAndExactToolGates() {
        ProductionSkillPlanCompiler compiler =
                ProductionSkillPlanCompiler.p5aDefault();
        SkillPlan first = compiler.compileWoodToIronPick(FIRST_BOT, 1L);
        SkillPlan sameTemplateDifferentRunContext =
                compiler.compileWoodToIronPick(SECOND_BOT, 99L);
        ProductionPlanTemplate template = WoodToIronPickTemplate.create();

        assertEquals(14, template.nodes().size(),
                "模板包含两个真实工作站放置逻辑节点");
        assertEquals(38, first.nodes().size(),
                "33 个物理生产动作外，还必须有五道独立精确主手门");
        assertEquals(45, first.edges().size(),
                "21 条逻辑边和 19 条 fragment 串行边经五道装备门重接线");
        assertEquals(first.planId(), sameTemplateDifferentRunContext.planId());
        assertNotEquals(first.botId(), sameTemplateDifferentRunContext.botId());
        assertNotEquals(first.revision(), sameTemplateDifferentRunContext.revision());

        Map<String, SkillPlanNode> byOperationId = new LinkedHashMap<>();
        Map<String, SkillPlanNode> byExactMainHandItemId =
                new LinkedHashMap<>();
        for (SkillPlanNode node : first.nodes()) {
            assertEquals(P5ABuiltinSkillIds.VERSION, node.skillVersion());
            assertEquals(
                    1,
                    node.parameters().values().size(),
                    "compiler must not smuggle coordinates, commands, sessions, or run ids into parameters");
            if (node.skillId().equals(P5ABuiltinSkillIds.BOOTSTRAP_IRON)) {
                Object operationId = node.parameters().values().get(
                        P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER);
                assertTrue(operationId instanceof String);
                assertTrue(byOperationId.put((String) operationId, node)
                        == null, "production operation id must be unique");
            } else if (node.skillId().equals(
                    P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND)) {
                Object itemId = node.parameters().values().get(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER);
                assertTrue(itemId instanceof String);
                assertTrue(byExactMainHandItemId.put((String) itemId, node)
                        == null, "exact main-hand item id must be unique");
            } else {
                throw new AssertionError(
                        "canonical compiler emitted an unexpected skill "
                                + node.skillId());
            }
        }

        assertEquals(33, byOperationId.size());
        assertEquals(5, byExactMainHandItemId.size());
        assertEquals(33, ProductionSkillPlanCompiler.approvedOperationIds()
                .size());
        Map<String, SkillPlanNode> secondByOperationId = new LinkedHashMap<>();
        Map<String, SkillPlanNode> secondByExactMainHandItemId =
                new LinkedHashMap<>();
        for (SkillPlanNode node : sameTemplateDifferentRunContext.nodes()) {
            if (node.skillId().equals(P5ABuiltinSkillIds.BOOTSTRAP_IRON)) {
                secondByOperationId.put((String) node.parameters().values()
                        .get(P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER), node);
            } else if (node.skillId().equals(
                    P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND)) {
                secondByExactMainHandItemId.put((String) node.parameters()
                        .values().get(P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER), node);
            } else {
                throw new AssertionError(
                        "canonical compiler emitted an unexpected skill "
                                + node.skillId());
            }
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
            if (isExactMainHandReroutedEdge(source)) {
                continue;
            }
            List<String> beforeFragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.beforeNodeId());
            List<String> afterFragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.afterNodeId());
            expectedEdges.add(new SkillPlanEdge(
                    byOperationId.get(beforeFragments.get(
                            beforeFragments.size() - 1)).nodeId(),
                    byOperationId.get(afterFragments.get(0)).nodeId()));
        }
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                "minecraft:crafting_table",
                "crafting_table",
                List.of("place_crafting_table"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                "minecraft:wooden_pickaxe",
                "wooden_pickaxe",
                List.of("mine_cobblestone"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                "minecraft:furnace",
                "craft_furnace",
                List.of("place_furnace"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                "minecraft:stone_pickaxe",
                "stone_pickaxe",
                List.of("mine_raw_iron", "mine_coal"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                "minecraft:iron_pickaxe",
                "iron_pickaxe",
                List.of());
        assertEquals(expectedEdges, first.edges());
        assertEquals(first.edges(), sameTemplateDifferentRunContext.edges());
        assertExactMainHandNode(byExactMainHandItemId,
                secondByExactMainHandItemId, "minecraft:crafting_table");
        assertExactMainHandNode(byExactMainHandItemId,
                secondByExactMainHandItemId, "minecraft:wooden_pickaxe");
        assertExactMainHandNode(byExactMainHandItemId,
                secondByExactMainHandItemId, "minecraft:furnace");
        assertExactMainHandNode(byExactMainHandItemId,
                secondByExactMainHandItemId, "minecraft:stone_pickaxe");
        assertExactMainHandNode(byExactMainHandItemId,
                secondByExactMainHandItemId, "minecraft:iron_pickaxe");

        SkillRegistry registry = new SkillRegistry();
        assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(ProductionSkillPlanCompiler.handlerDescriptor()));
        assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(exactMainHandDescriptor()));
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

    private static boolean isExactMainHandReroutedEdge(
            ProductionPlanEdge edge) {
        return EXACT_MAIN_HAND_REROUTED_EDGES.contains(edge);
    }

    private static void appendExactMainHandEdges(
            List<SkillPlanEdge> edges,
            Map<String, SkillPlanNode> byOperationId,
            Map<String, SkillPlanNode> byExactMainHandItemId,
            String itemId,
            String sourceProductionNodeId,
            List<String> dependentProductionNodeIds) {
        SkillPlanNode equip = byExactMainHandItemId.get(itemId);
        assertTrue(equip != null,
                () -> "missing exact main-hand gate for " + itemId);
        List<String> sourceFragments = ProductionSkillPlanCompiler
                .operationIdsForProductionNode(sourceProductionNodeId);
        edges.add(new SkillPlanEdge(
                byOperationId.get(sourceFragments.get(
                        sourceFragments.size() - 1)).nodeId(),
                equip.nodeId()));
        for (String dependentProductionNodeId :
                dependentProductionNodeIds) {
            List<String> dependentFragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(dependentProductionNodeId);
            edges.add(new SkillPlanEdge(
                    equip.nodeId(),
                    byOperationId.get(dependentFragments.get(0)).nodeId()));
        }
    }

    private static void assertExactMainHandNode(
            Map<String, SkillPlanNode> first,
            Map<String, SkillPlanNode> second,
            String itemId) {
        SkillPlanNode firstNode = first.get(itemId);
        SkillPlanNode secondNode = second.get(itemId);
        assertTrue(firstNode != null,
                () -> "missing exact main-hand node for " + itemId);
        assertTrue(secondNode != null,
                () -> "second plan is missing exact main-hand node for "
                        + itemId);
        assertEquals(Map.of(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                        itemId),
                firstNode.parameters().values());
        assertEquals(firstNode.nodeId(), secondNode.nodeId(),
                "exact main-hand UUID must be derived from fixed plan semantics");
    }

    private static SkillDescriptor exactMainHandDescriptor() {
        return new SkillDescriptor(
                P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND,
                P5ABuiltinSkillIds.VERSION,
                SkillCategory.SURVIVAL,
                new SkillParameterSchema(Map.of(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                        new SkillParameterRule.StringRule(
                                true,
                                17,
                                24,
                                Set.of(
                                        "minecraft:wooden_pickaxe",
                                        "minecraft:stone_pickaxe",
                                        "minecraft:iron_pickaxe",
                                        "minecraft:crafting_table",
                                        "minecraft:furnace")))),
                SkillRiskLevel.LOW,
                Set.of(),
                240,
                0,
                true);
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
    void exactMainHandDescriptorAcceptsOnlyTheClosedP5aWhitelist() {
        SkillDescriptor descriptor = exactMainHandDescriptor();

        for (String itemId : List.of(
                "minecraft:wooden_pickaxe",
                "minecraft:stone_pickaxe",
                "minecraft:iron_pickaxe",
                "minecraft:crafting_table",
                "minecraft:furnace")) {
            assertTrue(descriptor.parameterSchema().validate(
                    new SkillParameters(Map.of(
                            P5ABuiltinSkillIds
                                    .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                            itemId))).valid());
        }
        assertFalse(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                        "minecraft:diamond_pickaxe"))).valid());
        assertFalse(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                        "minecraft:iron_pickaxe",
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
