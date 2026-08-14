package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 单次 AI 请求可使用的静态工具策略。
 *
 * <p>该策略由可信 Java 侧构造。它不接收模型的 owner、bot、世界或权限声明；这些动态事实将由
 * 后续会话与计划校验层重新确认。
 */
public record ToolFirewallPolicy(
        Map<String, ToolDefinition> knownTools,
        Set<String> requestWhitelist,
        ToolRiskLevel maximumRisk,
        int maximumCalls) {
    public ToolFirewallPolicy {
        Objects.requireNonNull(knownTools, "knownTools");
        Map<String, ToolDefinition> copiedTools = new LinkedHashMap<>();
        for (Map.Entry<String, ToolDefinition> entry : knownTools.entrySet()) {
            String toolName = ToolChecks.toolName(entry.getKey(), "known tool name");
            ToolDefinition definition = Objects.requireNonNull(
                    entry.getValue(), "tool definition");
            if (!toolName.equals(definition.toolName())) {
                throw new IllegalArgumentException(
                        "known tool key must match definition name");
            }
            if (copiedTools.put(toolName, definition) != null) {
                throw new IllegalArgumentException("duplicate known tool " + toolName);
            }
        }
        knownTools = Map.copyOf(copiedTools);

        Objects.requireNonNull(requestWhitelist, "requestWhitelist");
        Set<String> copiedWhitelist = new LinkedHashSet<>();
        for (String toolName : requestWhitelist) {
            String normalized = ToolChecks.toolName(toolName, "request whitelist tool");
            if (!knownTools.containsKey(normalized)) {
                throw new IllegalArgumentException(
                        "request whitelist references an unknown tool");
            }
            copiedWhitelist.add(normalized);
        }
        requestWhitelist = Set.copyOf(copiedWhitelist);
        Objects.requireNonNull(maximumRisk, "maximumRisk");
        if (maximumCalls < 1 || maximumCalls > ProposedSkillPlan.MAX_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "maximumCalls must be between 1 and "
                            + ProposedSkillPlan.MAX_TOOL_CALLS);
        }
    }
}
