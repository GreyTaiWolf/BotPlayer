package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * DAG 中一个稳定命名的生产步骤。
 */
public record ProductionPlanNode(
        String nodeId, ProductionOperation operation, boolean checkpoint) {
    public static final int MAX_NODE_ID_LENGTH = 64;
    private static final Pattern NODE_ID =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    public ProductionPlanNode {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeId.length() > MAX_NODE_ID_LENGTH
                || !NODE_ID.matcher(nodeId).matches()) {
            throw new IllegalArgumentException(
                    "production node id must be a bounded lower-case identifier");
        }
        Objects.requireNonNull(operation, "operation");
    }
}
