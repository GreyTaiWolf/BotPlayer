package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 对 P5A 生产 DAG 的集中 fail-closed 验证。
 *
 * <p>验证通过之前不会暴露任何可调度节点。它同时验证 schema、未知配方、精确菜单族、
 * 预算、依赖图和逐步骤账本可满足性；因此后续 Minecraft 适配层只需把已解析的合同绑定到
 * 权威菜单快照，而不能重新解释输入。
 */
public final class ProductionPlanValidator {
    private final ProductionRecipeCatalog catalog;

    public ProductionPlanValidator(ProductionRecipeCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog, "catalog");
    }

    public static ProductionPlanValidator p5aDefault() {
        return new ProductionPlanValidator(
                ProductionRecipeCatalog.p5aDefault());
    }

    public ProductionPlanValidation validate(ProductionPlanTemplate template) {
        Objects.requireNonNull(template, "template");
        List<ProductionPlanViolation> violations = new ArrayList<>();
        ProductionBudgetUsage baseUsage = new ProductionBudgetUsage(
                template.nodes().size(), template.edges().size(), 0, 0, 0);
        if (!ProductionSchemas.isKnownExact(template.schema())) {
            violations.add(violation("plan",
                    ProductionPlanViolationCode.UNKNOWN_SCHEMA));
        }

        Map<String, ProductionPlanNode> nodesById = new LinkedHashMap<>();
        for (ProductionPlanNode node : template.nodes()) {
            if (nodesById.put(node.nodeId(), node) != null) {
                violations.add(violation(node.nodeId(),
                        ProductionPlanViolationCode.DUPLICATE_NODE_ID));
            }
        }

        Set<ProductionPlanEdge> uniqueEdges = new HashSet<>();
        for (ProductionPlanEdge edge : template.edges()) {
            if (!uniqueEdges.add(edge)) {
                violations.add(violation(edge.beforeNodeId(),
                        ProductionPlanViolationCode.DUPLICATE_EDGE));
            }
            if (!nodesById.containsKey(edge.beforeNodeId())) {
                violations.add(violation(edge.beforeNodeId(),
                        ProductionPlanViolationCode.UNKNOWN_EDGE_ENDPOINT));
            }
            if (!nodesById.containsKey(edge.afterNodeId())) {
                violations.add(violation(edge.afterNodeId(),
                        ProductionPlanViolationCode.UNKNOWN_EDGE_ENDPOINT));
            }
        }
        if (!violations.isEmpty()) {
            return ProductionPlanValidation.rejected(violations, baseUsage);
        }

        List<ProductionPlanNode> ordered = topologicalOrder(
                template.nodes(), template.edges());
        if (ordered.size() != template.nodes().size()) {
            return ProductionPlanValidation.rejected(List.of(violation("plan",
                    ProductionPlanViolationCode.CYCLIC_DAG)), baseUsage);
        }

        ProductionLedger projectedLedger = ProductionLedger.empty();
        ProductionBudgetUsage usage = baseUsage;
        List<ProductionResolvedNode> resolved = new ArrayList<>();
        for (ProductionPlanNode node : ordered) {
            Resolution resolution = resolve(template.schema(), node);
            if (resolution.violation().isPresent()) {
                violations.add(resolution.violation().orElseThrow());
                continue;
            }
            usage = addUsage(usage, resolution.usage(), violations,
                    node.nodeId());
            if (!violations.isEmpty()) {
                continue;
            }
            Optional<ProductionLedger> after = resolution.node()
                    .orElseThrow()
                    .expectedPlayerDelta()
                    .applyTo(projectedLedger);
            if (after.isEmpty()) {
                violations.add(violation(node.nodeId(),
                        ProductionPlanViolationCode.UNSATISFIED_LEDGER));
                continue;
            }
            projectedLedger = after.orElseThrow();
            resolved.add(resolution.node().orElseThrow());
        }

        if (!template.schema().maximumBudget().allows(usage)
                || !template.requestedBudget().allows(usage)) {
            violations.add(violation("plan",
                    ProductionPlanViolationCode.BUDGET_EXCEEDED));
        }
        if (!projectedLedger.containsAtLeast(
                template.schema().requiredFinalOutput())) {
            violations.add(violation("plan",
                    ProductionPlanViolationCode.FINAL_OUTPUT_MISSING));
        }
        if (!violations.isEmpty()) {
            return ProductionPlanValidation.rejected(violations, usage);
        }
        return ProductionPlanValidation.accepted(
                resolved, projectedLedger, usage);
    }

    private Resolution resolve(
            ProductionSchema schema, ProductionPlanNode node) {
        ProductionOperation operation = node.operation();
        if (operation instanceof ResourceAcquisition acquisition) {
            if (!schema.allowsAcquisition(acquisition.expectedGain())) {
                return Resolution.rejected(violation(node.nodeId(),
                        ProductionPlanViolationCode.ACQUISITION_NOT_ALLOWED));
            }
            if (!matchesAcquisitionMethod(acquisition)) {
                return Resolution.rejected(violation(node.nodeId(),
                        ProductionPlanViolationCode.ACQUISITION_METHOD_MISMATCH));
            }
            return Resolution.accepted(new ProductionResolvedNode(
                    node, acquisition.expectedDelta(), Optional.empty(),
                    Optional.empty()), zeroUsage());
        }
        if (operation instanceof RecipeExecution execution) {
            Optional<ProductionRecipe> recipe = catalog.find(
                    execution.recipeId());
            if (recipe.isEmpty()) {
                return Resolution.rejected(violation(node.nodeId(),
                        ProductionPlanViolationCode.UNKNOWN_RECIPE));
            }
            if (!schema.allowsRecipe(execution.recipeId())) {
                return Resolution.rejected(violation(node.nodeId(),
                        ProductionPlanViolationCode.RECIPE_NOT_ALLOWED));
            }
            try {
                ProductionRecipe known = recipe.orElseThrow();
                ProductionMenuContract contract = known.menuContract(
                        execution.batches());
                Optional<FurnaceBatchRequirement> furnace =
                        known.furnaceRequirement(execution.batches());
                int furnaceInputs = furnace.map(
                        FurnaceBatchRequirement::inputCount).orElse(0);
                ProductionBudgetUsage operationUsage =
                        new ProductionBudgetUsage(
                                0,
                                0,
                                execution.batches(),
                                contract.maximumClicks(),
                                furnaceInputs);
                return Resolution.accepted(new ProductionResolvedNode(
                        node,
                        contract.expectedPlayerDelta(),
                        Optional.of(contract),
                        furnace), operationUsage);
            } catch (IllegalArgumentException exception) {
                return Resolution.rejected(violation(node.nodeId(),
                        ProductionPlanViolationCode.OPERATION_OUTSIDE_LIMIT));
            }
        }
        if (operation instanceof SingleChestTransfer transfer) {
            ProductionMenuContract contract = transfer.menuContract();
            return Resolution.accepted(new ProductionResolvedNode(
                    node,
                    contract.expectedPlayerDelta(),
                    Optional.of(contract),
                    Optional.empty()), new ProductionBudgetUsage(
                            0, 0, 1, contract.maximumClicks(), 0));
        }
        return Resolution.rejected(violation(node.nodeId(),
                ProductionPlanViolationCode.OPERATION_OUTSIDE_LIMIT));
    }

    private static boolean matchesAcquisitionMethod(
            ResourceAcquisition acquisition) {
        Set<ProductionMaterial> materials = acquisition.expectedGain()
                .quantities().keySet();
        return switch (acquisition.method()) {
            case HARVEST_LOG -> materials.equals(
                    Set.of(ProductionMaterials.OAK_LOG));
            case MINE_COBBLESTONE -> materials.equals(
                    Set.of(ProductionMaterials.COBBLESTONE));
            case MINE_RAW_IRON -> materials.equals(
                    Set.of(ProductionMaterials.RAW_IRON));
            case MINE_COAL -> materials.equals(
                    Set.of(ProductionMaterials.COAL));
        };
    }

    private static ProductionBudgetUsage addUsage(
            ProductionBudgetUsage current,
            ProductionBudgetUsage added,
            List<ProductionPlanViolation> violations,
            String nodeId) {
        try {
            return current.plus(added);
        } catch (IllegalArgumentException exception) {
            violations.add(violation(nodeId,
                    ProductionPlanViolationCode.OPERATION_OUTSIDE_LIMIT));
            return current;
        }
    }

    private static List<ProductionPlanNode> topologicalOrder(
            List<ProductionPlanNode> nodes, List<ProductionPlanEdge> edges) {
        Map<String, Integer> indegree = new LinkedHashMap<>();
        Map<String, List<String>> successors = new HashMap<>();
        Map<String, ProductionPlanNode> byId = new LinkedHashMap<>();
        for (ProductionPlanNode node : nodes) {
            indegree.put(node.nodeId(), 0);
            successors.put(node.nodeId(), new ArrayList<>());
            byId.put(node.nodeId(), node);
        }
        for (ProductionPlanEdge edge : edges) {
            successors.get(edge.beforeNodeId()).add(edge.afterNodeId());
            indegree.compute(edge.afterNodeId(), (ignored, count) ->
                    Integer.valueOf(Objects.requireNonNull(count) + 1));
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        for (Map.Entry<String, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.addLast(entry.getKey());
            }
        }
        List<ProductionPlanNode> ordered = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String next = ready.removeFirst();
            ordered.add(byId.get(next));
            for (String successor : successors.get(next)) {
                int remaining = indegree.compute(successor,
                        (ignored, count) -> Integer.valueOf(
                                Objects.requireNonNull(count) - 1));
                if (remaining == 0) {
                    ready.addLast(successor);
                }
            }
        }
        return ordered;
    }

    private static ProductionBudgetUsage zeroUsage() {
        return new ProductionBudgetUsage(0, 0, 0, 0, 0);
    }

    private static ProductionPlanViolation violation(
            String scope, ProductionPlanViolationCode code) {
        return new ProductionPlanViolation(scope, code);
    }

    private record Resolution(
            Optional<ProductionResolvedNode> node,
            Optional<ProductionPlanViolation> violation,
            ProductionBudgetUsage usage) {
        private static Resolution accepted(
                ProductionResolvedNode node, ProductionBudgetUsage usage) {
            return new Resolution(Optional.of(node), Optional.empty(), usage);
        }

        private static Resolution rejected(ProductionPlanViolation violation) {
            return new Resolution(Optional.empty(), Optional.of(violation),
                    zeroUsage());
        }
    }
}
