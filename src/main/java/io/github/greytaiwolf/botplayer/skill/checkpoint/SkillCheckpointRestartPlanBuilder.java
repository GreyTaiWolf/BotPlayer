package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.UUID;

/**
 * 把完整已批准计划和安全 checkpoint 状态裁剪为严格可恢复后缀。
 *
 * <p>完成节点必须是完整计划确定性拓扑顺序的连续前缀。后缀只保留其内部依赖；从该前缀
 * 指向后缀的输入边被确认后移除，反向边一律拒绝。新 planId 仅由完整计划内容引用与完成
 * 前缀派生，既不读取也不接受 checkpointId/runId。
 */
public final class SkillCheckpointRestartPlanBuilder {
    private static final String PLAN_ID_DOMAIN =
            "botplayer:checkpoint-restart-suffix:v1";
    private static final int MAX_PLAN_ID_ATTEMPTS = 8;

    private SkillCheckpointRestartPlanBuilder() {}

    public static SkillCheckpointRestartPlanBuild build(
            SkillPlan approvedFullPlan, SkillCheckpointRestartContext context) {
        Objects.requireNonNull(approvedFullPlan, "approvedFullPlan");
        Objects.requireNonNull(context, "context");
        if (!approvedFullPlan.botId().equals(context.botId())
                || !referenceOf(approvedFullPlan).equals(context.plan())) {
            return SkillCheckpointRestartPlanBuild.rejected(
                    SkillCheckpointRestartPlanBuild.Status
                            .SOURCE_PLAN_MISMATCH);
        }
        if (approvedFullPlan.nodes().isEmpty()) {
            return SkillCheckpointRestartPlanBuild.rejected(
                    SkillCheckpointRestartPlanBuild.Status.FULL_PLAN_EMPTY);
        }

        Map<UUID, SkillPlanNode> fullNodes = new LinkedHashMap<>();
        for (SkillPlanNode node : approvedFullPlan.nodes()) {
            if (fullNodes.putIfAbsent(node.nodeId(), node) != null) {
                return SkillCheckpointRestartPlanBuild.rejected(
                        SkillCheckpointRestartPlanBuild.Status
                                .FULL_PLAN_DUPLICATE_NODE);
            }
        }
        Map<UUID, SkillCheckpointNodeState> states = new HashMap<>();
        for (SkillCheckpointNode node : context.nodes()) {
            if (states.putIfAbsent(node.nodeId(), node.state()) != null) {
                return SkillCheckpointRestartPlanBuild.rejected(
                        SkillCheckpointRestartPlanBuild.Status
                                .CHECKPOINT_NODE_SET_MISMATCH);
            }
        }
        if (!fullNodes.keySet().equals(states.keySet())) {
            return SkillCheckpointRestartPlanBuild.rejected(
                    SkillCheckpointRestartPlanBuild.Status
                            .CHECKPOINT_NODE_SET_MISMATCH);
        }

        GraphBuild graph = graph(fullNodes.keySet(), approvedFullPlan.edges());
        if (graph.status().isPresent()) {
            return SkillCheckpointRestartPlanBuild.rejected(
                    graph.status().orElseThrow());
        }
        List<UUID> order = topologicalOrder(graph);
        if (order.size() != fullNodes.size()) {
            return SkillCheckpointRestartPlanBuild.rejected(
                    SkillCheckpointRestartPlanBuild.Status.CYCLIC_PLAN);
        }

        List<UUID> completedPrefix = new ArrayList<>();
        Set<UUID> completed = new LinkedHashSet<>();
        boolean reachedRestartableNode = false;
        for (UUID nodeId : order) {
            SkillCheckpointNodeState state = states.get(nodeId);
            if (state == SkillCheckpointNodeState.FAILED) {
                return SkillCheckpointRestartPlanBuild.rejected(
                        SkillCheckpointRestartPlanBuild.Status
                                .FAILED_NODE_PRESENT);
            }
            if (completed(state)) {
                if (reachedRestartableNode) {
                    return SkillCheckpointRestartPlanBuild.rejected(
                            SkillCheckpointRestartPlanBuild.Status
                                    .COMPLETED_NOT_PREFIX);
                }
                completed.add(nodeId);
                completedPrefix.add(nodeId);
            } else {
                reachedRestartableNode = true;
            }
        }

        List<SkillPlanNode> suffixNodes = approvedFullPlan.nodes().stream()
                .filter(node -> !completed.contains(node.nodeId()))
                .toList();
        if (suffixNodes.isEmpty()) {
            return SkillCheckpointRestartPlanBuild.rejected(
                    SkillCheckpointRestartPlanBuild.Status.EMPTY_SUFFIX);
        }
        List<SkillPlanEdge> suffixEdges = new ArrayList<>();
        for (SkillPlanEdge edge : approvedFullPlan.edges()) {
            boolean prerequisiteCompleted = completed.contains(
                    edge.prerequisiteNodeId());
            boolean dependentCompleted = completed.contains(
                    edge.dependentNodeId());
            if (dependentCompleted && !prerequisiteCompleted) {
                return SkillCheckpointRestartPlanBuild.rejected(
                        SkillCheckpointRestartPlanBuild.Status
                                .COMPLETED_DEPENDS_ON_SUFFIX);
            }
            if (!prerequisiteCompleted && !dependentCompleted) {
                suffixEdges.add(edge);
            }
        }

        SkillCheckpointPlan source = referenceOf(approvedFullPlan);
        SkillPlan suffix = new SkillPlan(
                deterministicPlanId(source, completedPrefix),
                approvedFullPlan.botId(),
                approvedFullPlan.revision(),
                suffixNodes,
                List.copyOf(suffixEdges));
        return SkillCheckpointRestartPlanBuild.built(
                new SkillCheckpointRestartPlan(
                        source, suffix, completedPrefix));
    }

    private static boolean completed(SkillCheckpointNodeState state) {
        return state == SkillCheckpointNodeState.SUCCEEDED
                || state == SkillCheckpointNodeState.SKIPPED;
    }

    private static GraphBuild graph(
            Set<UUID> nodeIds, List<SkillPlanEdge> edges) {
        Map<UUID, List<UUID>> outgoing = new HashMap<>();
        Map<UUID, Integer> indegree = new HashMap<>();
        for (UUID nodeId : nodeIds) {
            outgoing.put(nodeId, new ArrayList<>());
            indegree.put(nodeId, 0);
        }
        Set<SkillPlanEdge> unique = new HashSet<>();
        for (SkillPlanEdge edge : edges) {
            if (!unique.add(edge)) {
                return GraphBuild.invalid(
                        SkillCheckpointRestartPlanBuild.Status.DUPLICATE_EDGE);
            }
            if (edge.prerequisiteNodeId().equals(edge.dependentNodeId())
                    || !nodeIds.contains(edge.prerequisiteNodeId())
                    || !nodeIds.contains(edge.dependentNodeId())) {
                return GraphBuild.invalid(
                        SkillCheckpointRestartPlanBuild.Status.INVALID_EDGE);
            }
            outgoing.get(edge.prerequisiteNodeId()).add(
                    edge.dependentNodeId());
            indegree.compute(
                    edge.dependentNodeId(),
                    (ignored, value) -> Objects.requireNonNull(value) + 1);
        }
        outgoing.values().forEach(values -> values.sort(Comparator.naturalOrder()));
        return new GraphBuild(outgoing, indegree, Optional.empty());
    }

    private static List<UUID> topologicalOrder(GraphBuild graph) {
        Map<UUID, Integer> indegree = new HashMap<>(graph.indegree());
        PriorityQueue<UUID> ready = new PriorityQueue<>();
        for (Map.Entry<UUID, Integer> entry : indegree.entrySet()) {
            if (entry.getValue() == 0) {
                ready.add(entry.getKey());
            }
        }
        List<UUID> order = new ArrayList<>(indegree.size());
        while (!ready.isEmpty()) {
            UUID nodeId = ready.remove();
            order.add(nodeId);
            for (UUID dependent : graph.outgoing().get(nodeId)) {
                int remaining = indegree.compute(
                        dependent,
                        (ignored, value) -> Objects.requireNonNull(value) - 1);
                if (remaining == 0) {
                    ready.add(dependent);
                }
            }
        }
        return List.copyOf(order);
    }

    private static SkillCheckpointPlan referenceOf(SkillPlan plan) {
        return new SkillCheckpointPlan(
                plan.planId(),
                plan.revision(),
                SkillCheckpointBridge.planDigest(plan));
    }

    private static UUID deterministicPlanId(
            SkillCheckpointPlan source, List<UUID> completedPrefix) {
        for (int attempt = 0; attempt < MAX_PLAN_ID_ATTEMPTS; attempt++) {
            byte[] digest = digest(source, completedPrefix, attempt);
            ByteBuffer bytes = ByteBuffer.wrap(digest);
            long most = bytes.getLong();
            long least = bytes.getLong();
            most = (most & 0xffffffffffff0fffL) | 0x0000000000005000L;
            least = (least & 0x3fffffffffffffffL) | 0x8000000000000000L;
            UUID planId = new UUID(most, least);
            if (!planId.equals(source.planId())
                    && !(planId.getMostSignificantBits() == 0L
                            && planId.getLeastSignificantBits() == 0L)) {
                return planId;
            }
        }
        throw new IllegalStateException(
                "could not derive a distinct deterministic restart planId");
    }

    private static byte[] digest(
            SkillCheckpointPlan source,
            List<UUID> completedPrefix,
            int attempt) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, PLAN_ID_DOMAIN);
            update(digest, source.planId().toString());
            update(digest, Long.toString(source.revision()));
            update(digest, source.contentDigest());
            update(digest, Integer.toString(attempt));
            update(digest, Integer.toString(completedPrefix.size()));
            for (UUID nodeId : completedPrefix) {
                update(digest, nodeId.toString());
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private record GraphBuild(
            Map<UUID, List<UUID>> outgoing,
            Map<UUID, Integer> indegree,
            Optional<SkillCheckpointRestartPlanBuild.Status> status) {
        private static GraphBuild invalid(
                SkillCheckpointRestartPlanBuild.Status status) {
            return new GraphBuild(Map.of(), Map.of(), Optional.of(status));
        }
    }
}
