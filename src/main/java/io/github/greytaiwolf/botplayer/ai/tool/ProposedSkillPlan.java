package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 与一个 Provider 请求关联的受限工具提案批次。
 *
 * <p>这里的 {@code requestId} 只用于关联和审计，不能替代未来服务器会话层的 owner、bot、
 * generation、nonce、deadline 或 world revision 复核。
 */
public record ProposedSkillPlan(
        UUID requestId,
        List<ProposedToolCall> toolCalls) {
    public static final int MAX_TOOL_CALLS = 32;

    public ProposedSkillPlan {
        ToolChecks.requireNonZero(requestId, "requestId");
        Objects.requireNonNull(toolCalls, "toolCalls");
        if (toolCalls.isEmpty() || toolCalls.size() > MAX_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "toolCalls must contain between 1 and "
                            + MAX_TOOL_CALLS + " entries");
        }
        List<ProposedToolCall> copied = new ArrayList<>(toolCalls.size());
        Set<String> callIds = new HashSet<>();
        for (ProposedToolCall toolCall : toolCalls) {
            ProposedToolCall copiedCall = Objects.requireNonNull(
                    toolCall, "tool call");
            if (!callIds.add(copiedCall.callId())) {
                throw new IllegalArgumentException(
                        "duplicate tool call id " + copiedCall.callId());
            }
            copied.add(copiedCall);
        }
        toolCalls = List.copyOf(copied);
    }

    /** 模型提案只在受控服务端 gate 内使用，诊断不渲染 call 内容。 */
    @Override
    public String toString() {
        return "ProposedSkillPlan[requestId=" + requestId
                + ", toolCallCount=" + toolCalls.size() + "]";
    }
}
