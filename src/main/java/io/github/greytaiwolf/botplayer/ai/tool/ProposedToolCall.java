package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Codec 成功解析出的单个工具提案。
 *
 * <p>它仍是模型提供的非可信数据；静态 Firewall、未来 owner/ACL、revision 和世界校验通过前
 * 不得把它转换为任何 Action、Skill 或世界副作用。
 */
public record ProposedToolCall(
        String callId,
        String toolName,
        Map<String, ToolValue> arguments) {
    public static final int MAX_ARGUMENTS = 64;

    public ProposedToolCall {
        callId = ToolChecks.callId(callId, "callId");
        toolName = ToolChecks.toolName(toolName, "toolName");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.size() > MAX_ARGUMENTS) {
            throw new IllegalArgumentException(
                    "arguments exceeds maximum size " + MAX_ARGUMENTS);
        }
        Map<String, ToolValue> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ToolValue> entry : arguments.entrySet()) {
            String name = ToolChecks.parameterName(
                    entry.getKey(), "argument name");
            ToolValue value = Objects.requireNonNull(
                    entry.getValue(), "argument value");
            ToolValueSafety.requireWithinHardLimits(value);
            if (copied.put(name, value) != null) {
                throw new IllegalArgumentException(
                        "duplicate argument name " + name);
            }
        }
        arguments = Map.copyOf(copied);
    }

    /** 不可信 callId、工具名和参数树不进入默认日志格式。 */
    @Override
    public String toString() {
        return "ProposedToolCall[callIdLength=" + callId.length()
                + ", toolNameLength=" + toolName.length()
                + ", argumentCount=" + arguments.size() + "]";
    }
}
