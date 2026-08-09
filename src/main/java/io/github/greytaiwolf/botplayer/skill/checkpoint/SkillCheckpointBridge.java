package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeCheckpoint;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** 将运行时的纯检查点来源映射为严格持久化记录。 */
public final class SkillCheckpointBridge {
    private SkillCheckpointBridge() {}

    public static SkillCheckpoint checkpoint(
            UUID serverInstanceId,
            UUID playerId,
            SkillRuntimeCheckpoint runtime) {
        Objects.requireNonNull(serverInstanceId, "serverInstanceId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(runtime, "runtime");
        SkillRunView view = runtime.view();
        return new SkillCheckpoint(
                view.runId(),
                serverInstanceId,
                view.botId(),
                playerId,
                view.botGeneration(),
                view.runId(),
                new SkillCheckpointPlan(
                        view.planId(),
                        view.planRevision(),
                        planDigest(runtime.plan())),
                view.state().name().toLowerCase(Locale.ROOT),
                continuationState(view.state()),
                view.stateRevision(),
                0,
                0,
                view.updatedTick(),
                Optional.empty(),
                nodes(runtime.nodes()),
                List.of(new CheckpointEvidence(
                        "runtime.state",
                        view.safeSummary(),
                        view.updatedTick())),
                view.failureCode().map(code ->
                        code.name().toLowerCase(Locale.ROOT)));
    }

    /**
     * 将恢复后缀的运行时快照合并回原完整计划的 checkpoint 谱系。
     *
     * <p>恢复运行只能带着后缀 {@link SkillPlan}，但下一次重启仍必须以原已批准完整计划
     * 匹配。本方法保留 {@code prior.plan()}，把已完成前缀和当前后缀节点合并为完整节点集。
     * 它绝不复制旧 runId/checkpointId；输出只使用当前运行时新分配的 runId。
     */
    public static SkillCheckpoint recoveredCheckpoint(
            UUID serverInstanceId,
            UUID playerId,
            SkillRuntimeCheckpoint runtime,
            SkillCheckpoint prior,
            SkillCheckpointRestartPlan restartPlan) {
        Objects.requireNonNull(serverInstanceId, "serverInstanceId");
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(prior, "prior");
        Objects.requireNonNull(restartPlan, "restartPlan");
        SkillRunView view = runtime.view();
        requireRecoveredLineage(
                serverInstanceId, playerId, runtime, prior, restartPlan);

        boolean advancedGeneration = view.botGeneration() > prior.generation();
        int attemptCount = incrementIfAdvanced(
                prior.attemptCount(), SkillCheckpoint.MAX_ATTEMPTS,
                advancedGeneration, "attemptCount");
        int recoveryCount = incrementIfAdvanced(
                prior.recoveryCount(), SkillCheckpoint.MAX_RECOVERIES,
                advancedGeneration, "recoveryCount");
        return new SkillCheckpoint(
                view.runId(),
                serverInstanceId,
                view.botId(),
                playerId,
                view.botGeneration(),
                view.runId(),
                prior.plan(),
                view.state().name().toLowerCase(Locale.ROOT),
                continuationState(view.state()),
                view.stateRevision(),
                attemptCount,
                recoveryCount,
                view.updatedTick(),
                prior.scope(),
                mergedNodes(prior, runtime, restartPlan),
                List.of(
                        new CheckpointEvidence(
                                "runtime.state",
                                view.safeSummary(),
                                view.updatedTick()),
                        new CheckpointEvidence(
                                "recovery.suffix_merged",
                                "已合并恢复后缀的运行时节点状态",
                                view.updatedTick())),
                view.failureCode().map(code ->
                        code.name().toLowerCase(Locale.ROOT)));
    }

    /**
     * 在新 suffix run 入队之前耐久地消耗一次恢复预算。
     *
     * <p>这个 handoff 仍保留旧 generation：此刻尚未提交任何新动作，若进程在提交前
     * 或提交后首次安全点前中断，下一次新 body 仍能严格大于这份记录并重建同一后缀。
     * 但它会使用全新的 checkpoint/run token、递增的 state revision 与计数，因此旧
     * checkpoint 不能被无限重放。实际 suffix 的第一份安全 checkpoint 会以传入的
     * {@code prior} 重新合并，得到与本 handoff 相同的一次计数增量，而不会双计。
     */
    public static SkillCheckpoint recoveryHandoff(
            SkillCheckpoint prior, long handoffTick) {
        Objects.requireNonNull(prior, "prior");
        if (handoffTick < 0L) {
            throw new IllegalArgumentException(
                    "recovery handoff tick must be non-negative");
        }
        if (prior.continuationState().isTerminal()) {
            throw new IllegalArgumentException(
                    "terminal checkpoint cannot create a recovery handoff");
        }
        int attempts = incrementIfAdvanced(
                prior.attemptCount(), SkillCheckpoint.MAX_ATTEMPTS,
                true, "attemptCount");
        int recoveries = incrementIfAdvanced(
                prior.recoveryCount(), SkillCheckpoint.MAX_RECOVERIES,
                true, "recoveryCount");
        long revision;
        try {
            revision = Math.incrementExact(prior.stateRevision());
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "checkpoint state revision is exhausted for recovery handoff",
                    exception);
        }
        UUID checkpointId = freshHandoffToken(prior);
        UUID runId = freshHandoffToken(prior, checkpointId);
        return new SkillCheckpoint(
                checkpointId,
                prior.serverInstanceId(),
                prior.botId(),
                prior.playerId(),
                prior.generation(),
                runId,
                prior.plan(),
                prior.phase(),
                SkillCheckpointContinuationState.RESTARTABLE,
                revision,
                attempts,
                recoveries,
                handoffTick,
                prior.scope(),
                prior.nodes(),
                List.of(new CheckpointEvidence(
                        "recovery.handoff",
                        "恢复预算已耐久登记；新运行尚未派发任何动作",
                        handoffTick)),
                Optional.empty());
    }

    /**
     * 在任何可变世界动作进入 action mailbox 前，耐久地撤销上一份可恢复声明。
     *
     * <p>这不是“动作已经成功”的记录；它只表达一次原版动作可能在本次保存之后发生，
     * 所以旧安全点绝不能在掉电后重放。后续动作完成并重新到达静止点时，新的 restartable
     * checkpoint 会以更高 revision 覆盖本条 fence。若进程在两者之间中断，启动时只会看见
     * terminal record 并保守拒绝自动恢复。</p>
     */
    public static SkillCheckpoint dispatchFence(
            SkillCheckpoint prior, long currentTick) {
        return terminalFence(
                Objects.requireNonNull(prior, "prior"),
                currentTick,
                "dispatch_fenced",
                "checkpoint.dispatch_fenced",
                "原版动作派发前已撤销旧恢复授权");
    }

    /**
     * 生命周期关闭、取消或 terminal run 的耐久 tombstone。
     *
     * <p>删除 SavedData 记录必须先经过一次可恢复性为零的写入；否则“内存删掉后同步保存
     * 失败”会让下一 JVM 重新加载旧的 restartable 文件。tombstone 保留原计划和 node
     * 证据，仅用于阻止恢复；同 generation 的全新 run 必须使用不同 runId 才能随后写入
     * 新安全点。</p>
     */
    public static SkillCheckpoint terminalTombstone(
            SkillCheckpoint prior, long currentTick) {
        return terminalFence(
                Objects.requireNonNull(prior, "prior"),
                currentTick,
                "revoked",
                "checkpoint.revoked",
                "生命周期已撤销旧恢复授权");
    }

    private static SkillCheckpoint terminalFence(
            SkillCheckpoint prior,
            long currentTick,
            String phase,
            String evidenceCode,
            String evidenceSummary) {
        if (currentTick < 0L) {
            throw new IllegalArgumentException(
                    "checkpoint fence tick must be non-negative");
        }
        if (prior.continuationState().isTerminal()) {
            return prior;
        }
        final long nextRevision;
        try {
            nextRevision = Math.incrementExact(prior.stateRevision());
        } catch (ArithmeticException exception) {
            throw new IllegalStateException(
                    "checkpoint state revision is exhausted for terminal fence",
                    exception);
        }
        long observedTick = Math.max(prior.checkpointTick(), currentTick);
        return new SkillCheckpoint(
                prior.checkpointId(),
                prior.serverInstanceId(),
                prior.botId(),
                prior.playerId(),
                prior.generation(),
                prior.runId(),
                prior.plan(),
                phase,
                SkillCheckpointContinuationState.CANCELLED,
                nextRevision,
                prior.attemptCount(),
                prior.recoveryCount(),
                observedTick,
                prior.scope(),
                prior.nodes(),
                List.of(new CheckpointEvidence(
                        evidenceCode, evidenceSummary, observedTick)),
                Optional.empty());
    }

    private static void requireRecoveredLineage(
            UUID serverInstanceId,
            UUID playerId,
            SkillRuntimeCheckpoint runtime,
            SkillCheckpoint prior,
            SkillCheckpointRestartPlan restartPlan) {
        CheckpointNbt.requireNonZeroUuid(serverInstanceId, "serverInstanceId");
        CheckpointNbt.requireNonZeroUuid(playerId, "playerId");
        SkillRunView view = runtime.view();
        boolean advancedGeneration = view.botGeneration() > prior.generation();
        if (!prior.serverInstanceId().equals(serverInstanceId)
                || !prior.playerId().equals(playerId)
                || !prior.botId().equals(view.botId())
                || !prior.plan().equals(restartPlan.sourcePlan())
                || !runtime.plan().equals(restartPlan.suffixPlan())
                || !view.planId().equals(restartPlan.suffixPlan().planId())
                || view.planRevision() != restartPlan.suffixPlan().revision()
                || view.botGeneration() < prior.generation()) {
            throw new IllegalArgumentException(
                    "recovered checkpoint lineage does not match its authoritative inputs");
        }
        if ((advancedGeneration
                        && (view.runId().equals(prior.runId())
                                || view.runId().equals(prior.checkpointId())))
                || (!advancedGeneration
                        && !view.runId().equals(prior.runId()))
                || restartPlan.suffixPlan().planId().equals(prior.runId())
                || restartPlan.suffixPlan().planId().equals(
                        prior.checkpointId())) {
            throw new IllegalArgumentException(
                    "recovered checkpoint would reuse an old runtime token");
        }
        if (prior.continuationState().isTerminal()) {
            throw new IllegalArgumentException(
                    "terminal checkpoint cannot become a recovered lineage");
        }
    }

    private static int incrementIfAdvanced(
            int current, int maximum, boolean advanced, String name) {
        if (!advanced) {
            return current;
        }
        if (current >= maximum) {
            throw new IllegalStateException(
                    name + " recovery bound is exhausted");
        }
        return current + 1;
    }

    private static UUID freshHandoffToken(SkillCheckpoint prior) {
        return freshHandoffToken(prior, null);
    }

    private static UUID freshHandoffToken(
            SkillCheckpoint prior, UUID forbidden) {
        for (int attempt = 0; attempt < 8; attempt++) {
            UUID candidate = UUID.randomUUID();
            if (!candidate.equals(prior.checkpointId())
                    && !candidate.equals(prior.runId())
                    && !candidate.equals(forbidden)) {
                return candidate;
            }
        }
        throw new IllegalStateException(
                "could not allocate a fresh recovery handoff token");
    }

    private static List<SkillCheckpointNode> mergedNodes(
            SkillCheckpoint prior,
            SkillRuntimeCheckpoint runtime,
            SkillCheckpointRestartPlan restartPlan) {
        Map<UUID, SkillCheckpointNode> priorNodes = indexPriorNodes(
                prior.nodes());
        Set<UUID> suffixIds = new HashSet<>();
        for (SkillPlanNode node : restartPlan.suffixPlan().nodes()) {
            if (!suffixIds.add(node.nodeId())) {
                throw new IllegalArgumentException(
                        "restart suffix contains duplicate nodeId");
            }
        }
        Set<UUID> completed = new HashSet<>(restartPlan.completedPrefix());
        if (!java.util.Collections.disjoint(completed, suffixIds)
                || priorNodes.size() != completed.size() + suffixIds.size()
                || !priorNodes.keySet().containsAll(completed)
                || !priorNodes.keySet().containsAll(suffixIds)) {
            throw new IllegalArgumentException(
                    "recovered checkpoint nodes do not partition the original full plan");
        }
        for (UUID nodeId : completed) {
            SkillCheckpointNode node = priorNodes.get(nodeId);
            if (!completed(node.state())) {
                throw new IllegalArgumentException(
                        "recovery prefix contains a node not proven complete");
            }
        }
        Map<UUID, SkillRuntimeCheckpoint.Node> runtimeNodes =
                indexRuntimeNodes(runtime.nodes());
        if (!runtimeNodes.keySet().equals(suffixIds)) {
            throw new IllegalArgumentException(
                    "runtime checkpoint does not cover the exact restart suffix");
        }
        List<SkillCheckpointNode> result = new ArrayList<>(priorNodes.size());
        for (SkillCheckpointNode oldNode : prior.nodes()) {
            if (completed.contains(oldNode.nodeId())) {
                result.add(oldNode);
                continue;
            }
            SkillRuntimeCheckpoint.Node current = runtimeNodes.get(
                    oldNode.nodeId());
            result.add(new SkillCheckpointNode(
                    current.nodeId(),
                    checkpointState(current.state()),
                    0,
                    current.state() == SkillRuntimeCheckpoint.State.FAILED
                            ? Optional.of("runtime.failed")
                            : Optional.empty(),
                    List.of()));
        }
        return List.copyOf(result);
    }

    private static Map<UUID, SkillCheckpointNode> indexPriorNodes(
            List<SkillCheckpointNode> nodes) {
        Map<UUID, SkillCheckpointNode> indexed = new LinkedHashMap<>();
        for (SkillCheckpointNode node : nodes) {
            if (indexed.putIfAbsent(node.nodeId(), node) != null) {
                throw new IllegalArgumentException(
                        "prior checkpoint contains duplicate nodeId");
            }
        }
        return indexed;
    }

    private static Map<UUID, SkillRuntimeCheckpoint.Node> indexRuntimeNodes(
            List<SkillRuntimeCheckpoint.Node> nodes) {
        Map<UUID, SkillRuntimeCheckpoint.Node> indexed = new HashMap<>();
        for (SkillRuntimeCheckpoint.Node node : nodes) {
            if (indexed.putIfAbsent(node.nodeId(), node) != null) {
                throw new IllegalArgumentException(
                        "runtime checkpoint contains duplicate nodeId");
            }
        }
        return indexed;
    }

    private static boolean completed(SkillCheckpointNodeState state) {
        return state == SkillCheckpointNodeState.SUCCEEDED
                || state == SkillCheckpointNodeState.SKIPPED;
    }

    private static SkillCheckpointNodeState checkpointState(
            SkillRuntimeCheckpoint.State state) {
        return switch (state) {
            case PENDING -> SkillCheckpointNodeState.PENDING;
            case IN_PROGRESS -> SkillCheckpointNodeState.IN_PROGRESS;
            case SUCCEEDED -> SkillCheckpointNodeState.SUCCEEDED;
            case FAILED -> SkillCheckpointNodeState.FAILED;
        };
    }

    private static SkillCheckpointContinuationState continuationState(
            io.github.greytaiwolf.botplayer.skill.core.SkillRunState state) {
        return switch (state) {
            case CREATED,
                    PREPARING,
                    RUNNING,
                    WAITING_ACTION,
                    WAITING_NAVIGATION,
                    WAITING_MENU,
                    WAITING_QUERY,
                    WAITING_TIMER,
                    PAUSING,
                    PAUSED,
                    RESUMING,
                    RECOVERING,
                    VERIFYING -> SkillCheckpointContinuationState.RESTARTABLE;
            case SUCCEEDED -> SkillCheckpointContinuationState.SUCCEEDED;
            case FAILED -> SkillCheckpointContinuationState.FAILED;
            case CANCELLED -> SkillCheckpointContinuationState.CANCELLED;
            case PREEMPTED -> SkillCheckpointContinuationState.PREEMPTED;
        };
    }

    /**
     * 计划摘要覆盖节点身份、技能版本、参数与依赖边；它是 checkpoint 恢复时重新加载
     * 已批准 pack 的证据，不是可执行序列化格式。
     */
    public static String planDigest(SkillPlan plan) {
        Objects.requireNonNull(plan, "plan");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            update(digest, "plan", plan.planId().toString());
            update(digest, "bot", plan.botId().toString());
            update(digest, "revision", Long.toString(plan.revision()));
            List<SkillPlanNode> nodes = new ArrayList<>(plan.nodes());
            nodes.sort(Comparator.comparing(SkillPlanNode::nodeId));
            for (SkillPlanNode node : nodes) {
                update(digest, "node", node.nodeId().toString());
                update(digest, "skill", node.skillId().toString());
                update(digest, "version", node.skillVersion().toString());
                for (Map.Entry<String, Object> parameter :
                        node.parameters().values().entrySet()) {
                    update(digest, "parameter", parameter.getKey());
                    update(digest, "value", parameter.getValue().toString());
                    update(digest, "type", parameter.getValue()
                            .getClass().getName());
                }
            }
            List<SkillPlanEdge> edges = new ArrayList<>(plan.edges());
            edges.sort(Comparator.comparing(
                    SkillPlanEdge::prerequisiteNodeId).thenComparing(
                            SkillPlanEdge::dependentNodeId));
            for (SkillPlanEdge edge : edges) {
                update(digest, "edge.from",
                        edge.prerequisiteNodeId().toString());
                update(digest, "edge.to",
                        edge.dependentNodeId().toString());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }

    private static List<SkillCheckpointNode> nodes(
            List<SkillRuntimeCheckpoint.Node> runtimeNodes) {
        List<SkillCheckpointNode> result = new ArrayList<>(
                runtimeNodes.size());
        for (SkillRuntimeCheckpoint.Node node : runtimeNodes) {
            SkillCheckpointNodeState state = switch (node.state()) {
                case PENDING -> SkillCheckpointNodeState.PENDING;
                case IN_PROGRESS -> SkillCheckpointNodeState.IN_PROGRESS;
                case SUCCEEDED -> SkillCheckpointNodeState.SUCCEEDED;
                case FAILED -> SkillCheckpointNodeState.FAILED;
            };
            result.add(new SkillCheckpointNode(
                    node.nodeId(),
                    state,
                    0,
                    state == SkillCheckpointNodeState.FAILED
                            ? Optional.of("runtime.failed")
                            : Optional.empty(),
                    List.of()));
        }
        return List.copyOf(result);
    }

    private static void update(
            MessageDigest digest, String field, String value) {
        byte[] fieldBytes = field.getBytes(StandardCharsets.UTF_8);
        byte[] valueBytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (fieldBytes.length >>> 8));
        digest.update((byte) fieldBytes.length);
        digest.update(fieldBytes);
        digest.update((byte) (valueBytes.length >>> 24));
        digest.update((byte) (valueBytes.length >>> 16));
        digest.update((byte) (valueBytes.length >>> 8));
        digest.update((byte) valueBytes.length);
        digest.update(valueBytes);
    }
}
