package io.github.greytaiwolf.botplayer.client.ai;

import io.github.greytaiwolf.botplayer.ai.AiModelCapabilities;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 本地客户端的可信 DeepSeek 模型与函数目录。
 *
 * <p>模型列表由客户端配置/能力策略提供，而不是把某个模型别名写死在业务代码。Endpoint 不在
 * 此配置中：Provider 固定连接官方 HTTPS origin，避免把 Authorization 发往任意 URL。</p>
 */
public record DeepSeekProviderConfig(
        List<AiModelCapabilities> models,
        List<DeepSeekToolDefinition> tools,
        boolean streamResponses) {
    public static final int MAX_MODELS = 128;
    public static final int MAX_TOOLS = 128;

    public DeepSeekProviderConfig {
        Objects.requireNonNull(models, "models");
        if (models.isEmpty() || models.size() > MAX_MODELS) {
            throw new IllegalArgumentException("DeepSeek config model count is outside bounds");
        }
        List<AiModelCapabilities> copiedModels = new ArrayList<>(models.size());
        Set<String> modelNames = new HashSet<>();
        for (AiModelCapabilities model : models) {
            AiModelCapabilities checked = Objects.requireNonNull(model, "model");
            if (!modelNames.add(checked.model())) {
                throw new IllegalArgumentException(
                        "duplicate DeepSeek model " + checked.model());
            }
            copiedModels.add(checked);
        }
        copiedModels.sort(Comparator.comparing(AiModelCapabilities::model));
        models = List.copyOf(copiedModels);

        Objects.requireNonNull(tools, "tools");
        if (tools.size() > MAX_TOOLS) {
            throw new IllegalArgumentException(
                    "DeepSeek tool catalog exceeds " + MAX_TOOLS);
        }
        List<DeepSeekToolDefinition> copiedTools = new ArrayList<>(tools.size());
        Set<String> toolNames = new HashSet<>();
        for (DeepSeekToolDefinition tool : tools) {
            DeepSeekToolDefinition checked = Objects.requireNonNull(tool, "tool");
            if (!toolNames.add(checked.definition().toolName())) {
                throw new IllegalArgumentException(
                        "duplicate DeepSeek function " + checked.definition().toolName());
            }
            copiedTools.add(checked);
        }
        copiedTools.sort(Comparator.comparing(
                value -> value.definition().toolName()));
        tools = List.copyOf(copiedTools);
    }

    public Optional<AiModelCapabilities> findModel(String model) {
        Objects.requireNonNull(model, "model");
        return models.stream()
                .filter(candidate -> candidate.model().equals(model))
                .findFirst();
    }

    public boolean containsTool(String toolName) {
        return tools.stream().anyMatch(tool -> tool.definition()
                .toolName().equals(toolName));
    }

    /** 避免默认 record 递归输出完整工具描述及其 schema。 */
    @Override
    public String toString() {
        return "DeepSeekProviderConfig[modelCount=" + models.size()
                + ", toolCount=" + tools.size()
                + ", streamResponses=" + streamResponses + "]";
    }
}
