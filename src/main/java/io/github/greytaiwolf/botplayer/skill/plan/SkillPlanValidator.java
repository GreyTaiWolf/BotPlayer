package io.github.greytaiwolf.botplayer.skill.plan;

import io.github.greytaiwolf.botplayer.skill.core.SkillDescriptor;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameterViolation;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * 对技能存在性、参数、边、深度和循环做一次有界静态校验。
 */
public final class SkillPlanValidator {
    private final SkillRegistry registry;
    private final SkillPlanLimits limits;

    public SkillPlanValidator(
            SkillRegistry registry, SkillPlanLimits limits) {
        this.registry = Objects.requireNonNull(
                registry, "registry");
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    public SkillPlanValidation validate(SkillPlan plan) {
        Objects.requireNonNull(plan, "plan");
        Violations violations = new Violations();
        if (plan.nodes().isEmpty()) {
            violations.add(
                    SkillPlanViolation.Code.EMPTY_PLAN,
                    null,
                    "计划至少需要一个节点");
        }
        if (plan.nodes().size() > limits.maximumNodes()) {
            violations.add(
                    SkillPlanViolation.Code.NODE_LIMIT_EXCEEDED,
                    null,
                    "计划节点数超过服务端上限");
        }
        if (plan.edges().size() > limits.maximumEdges()) {
            violations.add(
                    SkillPlanViolation.Code.EDGE_LIMIT_EXCEEDED,
                    null,
                    "计划边数超过服务端上限");
        }

        Map<UUID, SkillPlanNode> nodes = new LinkedHashMap<>();
        for (int index = 0; index < plan.nodes().size(); index++) {
            SkillPlanNode node = plan.nodes().get(index);
            SkillPlanNode previous =
                    nodes.putIfAbsent(node.nodeId(), node);
            if (previous != null) {
                violations.add(
                        SkillPlanViolation.Code.DUPLICATE_NODE,
                        node.nodeId(),
                        "计划包含重复 nodeId");
                continue;
            }
            validateNode(node, violations);
        }

        Graph graph = buildGraph(
                plan.edges(), nodes, violations);
        Topology topology = topologicalOrder(
                nodes, graph);
        if (topology.order().size() != nodes.size()
                && !nodes.isEmpty()) {
            violations.add(
                    SkillPlanViolation.Code.CYCLE_DETECTED,
                    null,
                    "计划依赖图包含循环");
        }
        if (topology.maximumDepth() > limits.maximumDepth()) {
            violations.add(
                    SkillPlanViolation.Code.DEPTH_EXCEEDED,
                    null,
                    "计划依赖深度超过服务端上限");
        }

        boolean valid = violations.values.isEmpty()
                && !violations.truncated;
        return new SkillPlanValidation(
                valid,
                violations.values,
                valid ? topology.order() : List.of(),
                topology.maximumDepth(),
                violations.truncated);
    }

    private void validateNode(
            SkillPlanNode node, Violations violations) {
        if (!registry.containsId(node.skillId())) {
            violations.add(
                    SkillPlanViolation.Code.UNKNOWN_SKILL,
                    node.nodeId(),
                    "节点引用了未注册技能 " + node.skillId());
            return;
        }
        SkillDescriptor descriptor = registry
                .find(node.skillId(), node.skillVersion())
                .orElse(null);
        if (descriptor == null) {
            violations.add(
                    SkillPlanViolation.Code.SKILL_VERSION_MISMATCH,
                    node.nodeId(),
                    "节点引用了不可用技能版本 "
                            + node.skillVersion());
            return;
        }
        descriptor.parameterSchema()
                .validate(node.parameters())
                .violations()
                .forEach(parameter ->
                        violations.add(
                                SkillPlanViolation.Code
                                        .INVALID_PARAMETERS,
                                node.nodeId(),
                                parameterSummary(parameter)));
    }

    private static Graph buildGraph(
            List<SkillPlanEdge> edges,
            Map<UUID, SkillPlanNode> nodes,
            Violations violations) {
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        Map<UUID, Integer> indegree = new HashMap<>();
        nodes.keySet().forEach(nodeId -> {
            outgoing.put(nodeId, new ArrayList<>());
            indegree.put(nodeId, 0);
        });
        Set<SkillPlanEdge> uniqueEdges = new HashSet<>();
        for (SkillPlanEdge edge : edges) {
            if (!uniqueEdges.add(edge)) {
                violations.add(
                        SkillPlanViolation.Code.DUPLICATE_EDGE,
                        edge.dependentNodeId(),
                        "计划包含重复依赖边");
                continue;
            }
            if (edge.prerequisiteNodeId()
                    .equals(edge.dependentNodeId())) {
                violations.add(
                        SkillPlanViolation.Code.SELF_EDGE,
                        edge.dependentNodeId(),
                        "节点不能依赖自身");
                continue;
            }
            if (!nodes.containsKey(edge.prerequisiteNodeId())
                    || !nodes.containsKey(edge.dependentNodeId())) {
                violations.add(
                        SkillPlanViolation.Code.UNKNOWN_EDGE_NODE,
                        edge.dependentNodeId(),
                        "依赖边引用了不存在的节点");
                continue;
            }
            outgoing.get(edge.prerequisiteNodeId())
                    .add(edge.dependentNodeId());
            indegree.compute(
                    edge.dependentNodeId(),
                    (ignored, value) ->
                            Objects.requireNonNull(value) + 1);
        }
        Comparator<UUID> order = Comparator.naturalOrder();
        outgoing.values().forEach(values -> values.sort(order));
        return new Graph(outgoing, indegree);
    }

    private static Topology topologicalOrder(
            Map<UUID, SkillPlanNode> nodes,
            Graph graph) {
        Comparator<UUID> order = Comparator.naturalOrder();
        PriorityQueue<UUID> ready = new PriorityQueue<>(order);
        Map<UUID, Integer> indegree =
                new HashMap<>(graph.indegree());
        Map<UUID, Integer> depth = new HashMap<>();
        for (UUID nodeId : nodes.keySet()) {
            depth.put(nodeId, 1);
            if (indegree.get(nodeId) == 0) {
                ready.add(nodeId);
            }
        }

        List<UUID> result = new ArrayList<>(nodes.size());
        int maximumDepth = nodes.isEmpty() ? 0 : 1;
        while (!ready.isEmpty()) {
            UUID nodeId = ready.remove();
            result.add(nodeId);
            int currentDepth = depth.get(nodeId);
            maximumDepth = Math.max(maximumDepth, currentDepth);
            for (UUID dependent :
                    graph.outgoing().get(nodeId)) {
                depth.merge(
                        dependent,
                        currentDepth + 1,
                        Math::max);
                int remaining = indegree.compute(
                        dependent,
                        (ignored, value) ->
                                Objects.requireNonNull(value) - 1);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        return new Topology(
                List.copyOf(result), maximumDepth);
    }

    private static String parameterSummary(
            SkillParameterViolation violation) {
        return "参数 "
                + violation.field()
                + "："
                + violation.code().name();
    }

    private record Graph(
            Map<UUID, List<UUID>> outgoing,
            Map<UUID, Integer> indegree) {}

    private record Topology(
            List<UUID> order, int maximumDepth) {}

    private static final class Violations {
        private final List<SkillPlanViolation> values =
                new ArrayList<>();
        private boolean truncated;

        private void add(
                SkillPlanViolation.Code code,
                UUID nodeId,
                String summary) {
            if (values.size()
                    >= SkillPlanValidation.MAX_VIOLATIONS) {
                truncated = true;
                return;
            }
            values.add(new SkillPlanViolation(
                    code,
                    java.util.Optional.ofNullable(nodeId),
                    summary));
        }
    }
}
