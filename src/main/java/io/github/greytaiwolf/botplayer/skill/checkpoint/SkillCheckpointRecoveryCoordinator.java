package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.Objects;
import java.util.Optional;

/**
 * 将耐久 checkpoint 变成新 generation 的新 {@link SkillRunRequest}。
 *
 * <p>这个类不恢复旧运行时对象，也不从 checkpoint 重建可执行正文。它先以当前批准计划
 * 的重新计算摘要调用 {@link SkillCheckpointRecoveryDecider}，再把 decider continuation
 * 缩减为不含 checkpointId/runId 的 context，并由严格 builder 裁出已完成前缀之后的后缀。
 * 最终请求没有旧 runId、动作、菜单、cursor 或预约 token；{@code SkillRuntime} 会为它另行
 * 分配 runId 并重新取得所有瞬态资源。
 */
public final class SkillCheckpointRecoveryCoordinator {
    private SkillCheckpointRecoveryCoordinator() {}

    /**
     * 判定并物化一次重启请求。任何当前计划替换或严格后缀构建失败都会保守拒绝。
     */
    public static SkillCheckpointRecoveryCoordination coordinate(
            SkillCheckpointRecoveryCoordinationRequest request) {
        Objects.requireNonNull(request, "request");

        SkillCheckpointRecoveryAvailability availability = availability(
                request.approvedPlan(), request.allDescriptorsAvailable());
        SkillCheckpointRecoveryOutcome decision =
                SkillCheckpointRecoveryDecider.evaluate(
                        new SkillCheckpointRecoveryRequest(
                                request.source(),
                                request.serverInstanceId(),
                                request.botId(),
                                request.playerId(),
                                request.newGeneration(),
                                availability,
                                request.safety(),
                                request.reobservation()));
        if (decision.disposition()
                == SkillCheckpointRecoveryDisposition.REJECT) {
            return SkillCheckpointRecoveryCoordination.decisionRejected(
                    decision);
        }

        SkillCheckpointRestartContext context = SkillCheckpointRestartContext
                .from(decision.continuation().orElseThrow());
        SkillPlan approvedPlan =
                request.approvedPlan().orElse(null);
        if (approvedPlan == null) {
            return SkillCheckpointRecoveryCoordination.restartPlanRejected(
                    decision);
        }
        SkillCheckpointRestartPlanBuild build;
        try {
            build = SkillCheckpointRestartPlanBuilder.build(
                    approvedPlan, context);
        } catch (RuntimeException exception) {
            return SkillCheckpointRecoveryCoordination.restartPlanRejected(
                    decision);
        }
        if (build.status() != SkillCheckpointRestartPlanBuild.Status.BUILT) {
            return SkillCheckpointRecoveryCoordination.restartPlanRejected(
                    decision);
        }
        SkillCheckpointRestartPlan restartPlan = build
                .restartPlan()
                .orElseThrow();
        return SkillCheckpointRecoveryCoordination.ready(
                decision,
                new SkillRunRequest(
                        request.botId(),
                        request.newGeneration(),
                        restartPlan.suffixPlan(),
                        request.submittedTick()),
                restartPlan);
    }

    private static SkillCheckpointRecoveryAvailability availability(
            Optional<SkillPlan> approvedPlan,
            boolean allDescriptorsAvailable) {
        return new SkillCheckpointRecoveryAvailability(
                approvedPlan.map(SkillCheckpointRecoveryCoordinator::referenceOf),
                allDescriptorsAvailable);
    }

    private static SkillCheckpointPlan referenceOf(SkillPlan plan) {
        Objects.requireNonNull(plan, "plan");
        return new SkillCheckpointPlan(
                plan.planId(),
                plan.revision(),
                SkillCheckpointBridge.planDigest(plan));
    }
}
