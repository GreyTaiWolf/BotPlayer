package io.github.greytaiwolf.botplayer.ai.tool;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 一个由服务器代码静态提供的工具 schema；模型不能创建或修改它。 */
public record ToolDefinition(
        String toolName,
        ToolRiskLevel riskLevel,
        Map<String, ToolParameterRule> parameters) {
    public static final int MAX_PARAMETERS = ProposedToolCall.MAX_ARGUMENTS;

    public ToolDefinition {
        toolName = ToolChecks.toolName(toolName, "toolName");
        Objects.requireNonNull(riskLevel, "riskLevel");
        Objects.requireNonNull(parameters, "parameters");
        if (parameters.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException(
                    "parameters exceeds maximum size " + MAX_PARAMETERS);
        }
        Map<String, ToolParameterRule> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ToolParameterRule> entry : parameters.entrySet()) {
            String parameterName = ToolChecks.parameterName(
                    entry.getKey(), "parameter name");
            ToolParameterRule rule = Objects.requireNonNull(
                    entry.getValue(), "parameter rule");
            if (copied.put(parameterName, rule) != null) {
                throw new IllegalArgumentException(
                        "duplicate parameter rule " + parameterName);
            }
        }
        parameters = Map.copyOf(copied);
    }
}
