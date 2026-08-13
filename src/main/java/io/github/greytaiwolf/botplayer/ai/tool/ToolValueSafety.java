package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Objects;

/** 供 DTO 与 Firewall 共用的值树深度、节点数和名称安全检查。 */
final class ToolValueSafety {
    private ToolValueSafety() {
        throw new AssertionError("No instances");
    }

    static void requireWithinHardLimits(ToolValue root) {
        Objects.requireNonNull(root, "root");
        Deque<Frame> pending = new ArrayDeque<>();
        pending.addLast(new Frame(root, 1));
        int nodes = 0;
        while (!pending.isEmpty()) {
            Frame frame = pending.removeLast();
            if (frame.depth() > ToolValue.MAX_TREE_DEPTH) {
                throw new IllegalArgumentException(
                        "tool value exceeds maximum depth "
                                + ToolValue.MAX_TREE_DEPTH);
            }
            nodes++;
            if (nodes > ToolValue.MAX_TREE_NODES) {
                throw new IllegalArgumentException(
                        "tool value exceeds maximum node count "
                                + ToolValue.MAX_TREE_NODES);
            }
            if (frame.value() instanceof ToolValue.ArrayValue arrayValue) {
                for (ToolValue child : arrayValue.values()) {
                    pending.addLast(new Frame(child, frame.depth() + 1));
                }
            } else if (frame.value() instanceof ToolValue.ObjectValue objectValue) {
                for (Map.Entry<String, ToolValue> entry
                        : objectValue.values().entrySet()) {
                    ToolChecks.parameterName(entry.getKey(), "object parameter name");
                    pending.addLast(new Frame(entry.getValue(), frame.depth() + 1));
                }
            }
        }
    }

    private record Frame(ToolValue value, int depth) {}
}
