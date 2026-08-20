package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import java.util.Objects;
import java.util.UUID;

/**
 * 节点处理器可见的最小运行上下文；不携带 Minecraft 活对象。
 *
 * <p>{@link #nextStateRevision()} 是处理器为异步回执登记的唯一 revision。
 * 处理器返回的 directive 会恰好消费这一 revision，迟到的旧回执由运行时丢弃。当前
 * Tick 可以等于 deadline：这只允许已入队的精确终态回执在 deadline 边界核验；普通
 * {@link SkillRuntime#tick(long)} 会在开始新工作前先处理 deadline，不能借此延长计划。
 */
public record SkillNodeContext(
        UUID runId,
        UUID botId,
        long botGeneration,
        long planRevision,
        long stateRevision,
        long nextStateRevision,
        SkillPlanNode node,
        int nodeIndex,
        long currentTick,
        long deadlineTick,
        ResourceReservationService reservations) {
    public SkillNodeContext {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L
                || planRevision <= 0L
                || stateRevision < 0L
                || nextStateRevision != stateRevision + 1L
                || nodeIndex < 0
                || currentTick < 0L
                || deadlineTick < currentTick) {
            throw new IllegalArgumentException(
                    "skill node context bounds are invalid");
        }
        node = Objects.requireNonNull(node, "node");
        reservations = Objects.requireNonNull(reservations, "reservations");
    }
}
