package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 通用运行时导出的可持久化检查点来源；只包含计划和值对象，不携带动作或菜单实例。
 */
public record SkillRuntimeCheckpoint(
        SkillRunView view,
        SkillPlan plan,
        List<Node> nodes) {
    public SkillRuntimeCheckpoint {
        view = Objects.requireNonNull(view, "view");
        plan = Objects.requireNonNull(plan, "plan");
        nodes = List.copyOf(Objects.requireNonNull(nodes, "nodes"));
        if (!view.botId().equals(plan.botId())
                || view.planId() == null
                || !view.planId().equals(plan.planId())
                || view.planRevision() != plan.revision()
                || nodes.size() != plan.nodes().size()) {
            throw new IllegalArgumentException(
                    "runtime checkpoint does not match its plan");
        }
    }

    /** 节点状态以已完成串行前缀和当前节点推导，不能由外部伪造。 */
    public record Node(UUID nodeId, State state) {
        public Node {
            Objects.requireNonNull(nodeId, "nodeId");
            Objects.requireNonNull(state, "state");
        }
    }

    public enum State {
        PENDING,
        IN_PROGRESS,
        SUCCEEDED,
        FAILED
    }
}
