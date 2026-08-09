package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;

/**
 * 明确的先后依赖；边不表达资源流转的隐式猜测。
 */
public record ProductionPlanEdge(String beforeNodeId, String afterNodeId) {
    public ProductionPlanEdge {
        Objects.requireNonNull(beforeNodeId, "beforeNodeId");
        Objects.requireNonNull(afterNodeId, "afterNodeId");
        if (beforeNodeId.equals(afterNodeId)) {
            throw new IllegalArgumentException(
                    "production dependency edge must not self-reference");
        }
    }
}
