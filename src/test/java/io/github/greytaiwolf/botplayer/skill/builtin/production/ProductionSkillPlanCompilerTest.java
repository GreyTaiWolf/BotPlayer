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
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidation;
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
        assertEquals(57, first.nodes().size(),
                "33 个物理生产动作、五道精确主手门和 19 张资源 fragment 导航门必须全部存在");
        assertEquals(64, first.edges().size(),
                "每个资源物理 fragment 的入口必须经由自己的导航门重接线");
        assertEquals(first.planId(), sameTemplateDifferentRunContext.planId());
        assertNotEquals(first.botId(), sameTemplateDifferentRunContext.botId());
        assertNotEquals(first.revision(), sameTemplateDifferentRunContext.revision());

        Map<String, SkillPlanNode> byOperationId = new LinkedHashMap<>();
        Map<String, SkillPlanNode> byExactMainHandItemId =
                new LinkedHashMap<>();
        Map<UUID, SkillPlanNode> firstByNodeId =
                new LinkedHashMap<>();
        for (SkillPlanNode node : first.nodes()) {
            assertTrue(firstByNodeId.put(node.nodeId(), node) == null,
                    "SkillPlan node id must be unique");
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
            } else if (node.skillId().equals(
                    P5ABuiltinSkillIds.NAVIGATE_TO_RESOURCE)) {
                Object blockId = node.parameters().values().get(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER);
                assertTrue(blockId instanceof String);
                assertTrue(ProductionSkillPlanCompiler
                        .approvedResourceNavigationBlockId((String) blockId)
                        .isPresent());
            } else {
                throw new AssertionError(
                        "canonical compiler emitted an unexpected skill "
                                + node.skillId());
            }
        }

        assertEquals(33, byOperationId.size());
        assertEquals(5, byExactMainHandItemId.size());
        Map<String, SkillPlanNode> byResourceNavigationOperationId =
                resourceNavigationNodesByOperationId(first, firstByNodeId);
        assertEquals(19, byResourceNavigationOperationId.size());
        assertEquals(33, ProductionSkillPlanCompiler.approvedOperationIds()
                .size());
        Map<String, SkillPlanNode> secondByOperationId = new LinkedHashMap<>();
        Map<String, SkillPlanNode> secondByExactMainHandItemId =
                new LinkedHashMap<>();
        Map<UUID, SkillPlanNode> secondByNodeId =
                new LinkedHashMap<>();
        for (SkillPlanNode node : sameTemplateDifferentRunContext.nodes()) {
            assertTrue(secondByNodeId.put(node.nodeId(), node) == null,
                    "second SkillPlan node id must be unique");
            if (node.skillId().equals(P5ABuiltinSkillIds.BOOTSTRAP_IRON)) {
                secondByOperationId.put((String) node.parameters().values()
                        .get(P5ABuiltinSkillIds
                                .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER), node);
            } else if (node.skillId().equals(
                    P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND)) {
                secondByExactMainHandItemId.put((String) node.parameters()
                        .values().get(P5ABuiltinSkillIds
                                .EXACT_MAIN_HAND_ITEM_ID_PARAMETER), node);
            } else if (node.skillId().equals(
                    P5ABuiltinSkillIds.NAVIGATE_TO_RESOURCE)) {
                Object blockId = node.parameters().values().get(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER);
                assertTrue(blockId instanceof String);
                assertTrue(ProductionSkillPlanCompiler
                        .approvedResourceNavigationBlockId((String) blockId)
                        .isPresent());
            } else {
                throw new AssertionError(
                        "canonical compiler emitted an unexpected skill "
                                + node.skillId());
            }
        }
        Map<String, SkillPlanNode> secondByResourceNavigationOperationId =
                resourceNavigationNodesByOperationId(
                        sameTemplateDifferentRunContext, secondByNodeId);
        assertEquals(19, secondByResourceNavigationOperationId.size());
        for (ProductionPlanNode source : template.nodes()) {
            List<String> fragmentIds = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.nodeId());
            String expectedNavigationBlock = ProductionSkillPlanCompiler
                    .resourceNavigationBlockForProductionNode(source.nodeId())
                    .orElse(null);
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
                assertResourceNavigationFragment(
                        expectedNavigationBlock,
                        operationId,
                        byResourceNavigationOperationId,
                        secondByResourceNavigationOperationId);
                assertSinglePhysicalOperation(source, resolved);
            }
        }
        Map<String, String> expectedNavigationBlocks = Map.of(
                "harvest_logs", "minecraft:oak_log",
                "mine_cobblestone", "minecraft:cobblestone",
                "mine_raw_iron", "minecraft:iron_ore",
                "mine_coal", "minecraft:coal_ore");
        for (Map.Entry<String, String> entry : expectedNavigationBlocks
                .entrySet()) {
            String productionNodeId = entry.getKey();
            String blockId = entry.getValue();
            assertEquals(blockId, ProductionSkillPlanCompiler
                    .resourceNavigationBlockForProductionNode(
                            productionNodeId)
                    .orElseThrow());
        }
        assertTrue(ProductionSkillPlanCompiler
                .resourceNavigationBlockForProductionNode("craft_planks")
                .isEmpty());

        List<SkillPlanEdge> expectedEdges = new ArrayList<>();
        for (ProductionPlanNode source : template.nodes()) {
            String navigationBlock = ProductionSkillPlanCompiler
                    .resourceNavigationBlockForProductionNode(source.nodeId())
                    .orElse(null);
            if (navigationBlock == null) {
                continue;
            }
            List<String> fragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.nodeId());
            for (String operationId : fragments) {
                expectedEdges.add(new SkillPlanEdge(
                        resourceNavigationNodeForOperation(operationId,
                                byResourceNavigationOperationId).nodeId(),
                        byOperationId.get(operationId).nodeId()));
            }
        }
        for (ProductionPlanNode source : template.nodes()) {
            List<String> fragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.nodeId());
            for (int index = 1; index < fragments.size(); index++) {
                expectedEdges.add(new SkillPlanEdge(
                        byOperationId.get(fragments.get(index - 1)).nodeId(),
                        entryNodeForOperation(fragments.get(index),
                                byOperationId,
                                byResourceNavigationOperationId).nodeId()));
            }
        }
        for (ProductionPlanEdge source : template.edges()) {
            if (isExactMainHandReroutedEdge(source)) {
                continue;
            }
            List<String> beforeFragments = ProductionSkillPlanCompiler
                    .operationIdsForProductionNode(source.beforeNodeId());
            expectedEdges.add(new SkillPlanEdge(
                    byOperationId.get(beforeFragments.get(
                            beforeFragments.size() - 1)).nodeId(),
                    entryNodeForProductionNode(source.afterNodeId(),
                            byOperationId,
                            byResourceNavigationOperationId).nodeId()));
        }
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                byResourceNavigationOperationId,
                "minecraft:crafting_table",
                "crafting_table",
                List.of("place_crafting_table"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                byResourceNavigationOperationId,
                "minecraft:wooden_pickaxe",
                "wooden_pickaxe",
                List.of("mine_cobblestone"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                byResourceNavigationOperationId,
                "minecraft:furnace",
                "craft_furnace",
                List.of("place_furnace"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                byResourceNavigationOperationId,
                "minecraft:stone_pickaxe",
                "stone_pickaxe",
                List.of("mine_raw_iron", "mine_coal"));
        appendExactMainHandEdges(expectedEdges, byOperationId,
                byExactMainHandItemId,
                byResourceNavigationOperationId,
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
        assertEquals(
                SkillRegistry.RegisterStatus.REGISTERED,
                registry.register(ProductionSkillPlanCompiler
                        .resourceNavigationHandlerDescriptor()));
        SkillPlanValidation skillPlanValidation = new SkillPlanValidator(
                registry,
                SkillPlanLimits.defaults())
                .validate(first);
        assertTrue(skillPlanValidation.valid());
        assertEquals(50, skillPlanValidation.maximumDepth());
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
            Map<String, SkillPlanNode> byResourceNavigationOperationId,
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
            edges.add(new SkillPlanEdge(
                    equip.nodeId(),
                    entryNodeForProductionNode(dependentProductionNodeId,
                            byOperationId,
                            byResourceNavigationOperationId).nodeId()));
        }
    }

    private static SkillPlanNode entryNodeForProductionNode(
            String productionNodeId,
            Map<String, SkillPlanNode> byOperationId,
            Map<String, SkillPlanNode> byResourceNavigationOperationId) {
        List<String> fragments = ProductionSkillPlanCompiler
                .operationIdsForProductionNode(productionNodeId);
        return entryNodeForOperation(fragments.get(0), byOperationId,
                byResourceNavigationOperationId);
    }

    private static SkillPlanNode entryNodeForOperation(
            String operationId,
            Map<String, SkillPlanNode> byOperationId,
            Map<String, SkillPlanNode> byResourceNavigationOperationId) {
        SkillPlanNode navigation = byResourceNavigationOperationId.get(
                operationId);
        if (navigation != null) {
            return navigation;
        }
        SkillPlanNode operation = byOperationId.get(operationId);
        assertTrue(operation != null,
                () -> "missing production entry for " + operationId);
        return operation;
    }

    private static void assertResourceNavigationFragment(
            String expectedBlockId,
            String operationId,
            Map<String, SkillPlanNode> first,
            Map<String, SkillPlanNode> second) {
        SkillPlanNode navigation = first.get(operationId);
        if (expectedBlockId == null) {
            assertTrue(navigation == null,
                    () -> "non-resource fragment unexpectedly has a navigation gate: "
                            + operationId);
            return;
        }
        assertTrue(navigation != null,
                () -> "missing resource navigation gate for " + operationId);
        SkillPlanNode secondNavigation = second.get(operationId);
        assertTrue(secondNavigation != null,
                () -> "second plan misses resource navigation gate for "
                        + operationId);
        assertEquals(Map.of(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                        expectedBlockId),
                navigation.parameters().values());
        assertEquals(navigation.nodeId(), secondNavigation.nodeId(),
                "resource navigation UUID must derive only from canonical fragment semantics");
    }

    private static SkillPlanNode resourceNavigationNodeForOperation(
            String operationId,
            Map<String, SkillPlanNode> byResourceNavigationOperationId) {
        SkillPlanNode navigation = byResourceNavigationOperationId.get(
                operationId);
        assertTrue(navigation != null,
                () -> "missing resource navigation gate for " + operationId);
        return navigation;
    }

    private static Map<String, SkillPlanNode>
            resourceNavigationNodesByOperationId(
                    SkillPlan plan, Map<UUID, SkillPlanNode> byNodeId) {
        Map<String, SkillPlanNode> result = new LinkedHashMap<>();
        Map<UUID, String> operationByNavigationNode = new LinkedHashMap<>();
        int navigationNodeCount = 0;
        for (SkillPlanNode node : byNodeId.values()) {
            if (node.skillId().equals(P5ABuiltinSkillIds
                    .NAVIGATE_TO_RESOURCE)) {
                navigationNodeCount++;
            }
        }
        for (SkillPlanEdge edge : plan.edges()) {
            SkillPlanNode prerequisite = byNodeId.get(
                    edge.prerequisiteNodeId());
            if (prerequisite == null || !prerequisite.skillId().equals(
                    P5ABuiltinSkillIds.NAVIGATE_TO_RESOURCE)) {
                continue;
            }
            SkillPlanNode dependent = byNodeId.get(edge.dependentNodeId());
            assertTrue(dependent != null && dependent.skillId().equals(
                    P5ABuiltinSkillIds.BOOTSTRAP_IRON),
                    "resource navigation gate must only lead to a production fragment");
            Object operationId = dependent.parameters().values().get(
                    P5ABuiltinSkillIds
                            .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER);
            assertTrue(operationId instanceof String,
                    "resource navigation target must carry a production operation id");
            assertTrue(result.put((String) operationId, prerequisite) == null,
                    () -> "resource fragment has multiple navigation gates: "
                            + operationId);
            assertTrue(operationByNavigationNode.put(prerequisite.nodeId(),
                    (String) operationId) == null,
                    () -> "navigation gate has multiple production targets: "
                            + prerequisite.nodeId());
        }
        assertEquals(navigationNodeCount, result.size(),
                "every resource navigation gate must target exactly one fragment");
        return result;
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
    void resourceNavigationDescriptorAcceptsOnlyClosedExactResourceBlocks() {
        SkillDescriptor descriptor = ProductionSkillPlanCompiler
                .resourceNavigationHandlerDescriptor();
        Set<String> expected = Set.of(
                "minecraft:oak_log",
                "minecraft:cobblestone",
                "minecraft:iron_ore",
                "minecraft:coal_ore");

        assertEquals(expected, ProductionSkillPlanCompiler
                .approvedResourceNavigationBlockIds());
        for (String blockId : expected) {
            assertTrue(descriptor.parameterSchema().validate(
                    new SkillParameters(Map.of(
                            P5ABuiltinSkillIds
                                    .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                            blockId))).valid());
            assertEquals(blockId, ProductionSkillPlanCompiler
                    .approvedResourceNavigationBlockId(blockId)
                    .orElseThrow());
        }
        assertFalse(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                        "minecraft:deepslate_iron_ore"))).valid());
        assertFalse(descriptor.parameterSchema().validate(
                new SkillParameters(Map.of(
                        P5ABuiltinSkillIds
                                .RESOURCE_NAVIGATION_BLOCK_ID_PARAMETER,
                        "minecraft:oak_log",
                        "target.x",
                        4))).valid());
        assertTrue(ProductionSkillPlanCompiler
                .approvedResourceNavigationBlockId("minecraft:stone")
                .isEmpty());
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
