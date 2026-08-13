package io.github.greytaiwolf.botplayer.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.greytaiwolf.botplayer.ai.AiMessage;
import io.github.greytaiwolf.botplayer.ai.AiMessageRole;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.tool.ToolDefinition;
import io.github.greytaiwolf.botplayer.ai.tool.ToolParameterRule;
import io.github.greytaiwolf.botplayer.ai.tool.ToolStringSemantics;
import io.github.greytaiwolf.botplayer.ai.tool.ToolValueType;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * DeepSeek Chat Completions 的受控请求编码器。
 *
 * <p>只从已有的有界 DTO 和可信静态工具目录生成 JSON；不会把 credential、bot UUID、owner、
 * 文件路径或 Minecraft 活动对象序列化进去。</p>
 */
final class DeepSeekRequestCodec {
    private static final String JSON_OBJECT_SYSTEM_INSTRUCTION =
            "When returning message content, return exactly one valid JSON object and "
                    + "nothing else. Do not emit Markdown, prose, or code fences. "
                    + "If you call a provided function, use only that function interface.";

    String encode(
            AiRequest request,
            List<DeepSeekToolDefinition> toolCatalog,
            boolean streamResponses) {
        AiRequest checked = Objects.requireNonNull(request, "request");
        Objects.requireNonNull(toolCatalog, "toolCatalog");
        if (checked.options().responseFormat() == AiResponseFormat.JSON_SCHEMA) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.CAPABILITY_UNAVAILABLE);
        }
        requireUnicodeScalars(checked.model());
        if (checked.options().toolCallsAllowed() && toolCatalog.isEmpty()) {
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.TOOL_CATALOG_UNAVAILABLE);
        }
        if (checked.options().responseFormat() == AiResponseFormat.JSON_OBJECT
                && checked.messages().size() >= AiRequest.MAX_MESSAGES) {
            /* JSON mode has one non-negotiable provider-generated SYSTEM instruction. */
            throw new DeepSeekProviderException(
                    DeepSeekFailureCode.REQUEST_INVALID);
        }

        JsonObject root = new JsonObject();
        root.addProperty("model", checked.model());
        root.add("messages", encodeMessages(checked.messages(),
                checked.options().responseFormat() == AiResponseFormat.JSON_OBJECT));
        root.addProperty("max_tokens", checked.options().maximumOutputTokens());
        checked.options().temperature().ifPresent(value ->
                root.addProperty("temperature", value));
        JsonObject thinking = new JsonObject();
        thinking.addProperty("type", checked.options().reasoningAllowed()
                ? "enabled" : "disabled");
        root.add("thinking", thinking);

        if (checked.options().responseFormat() == AiResponseFormat.JSON_OBJECT) {
            JsonObject format = new JsonObject();
            format.addProperty("type", "json_object");
            root.add("response_format", format);
        }
        if (checked.options().toolCallsAllowed()) {
            root.add("tools", encodeTools(toolCatalog));
            root.addProperty("tool_choice", "auto");
        }
        if (streamResponses) {
            root.addProperty("stream", true);
            JsonObject streamOptions = new JsonObject();
            streamOptions.addProperty("include_usage", true);
            root.add("stream_options", streamOptions);
        }
        return root.toString();
    }

    private static JsonArray encodeMessages(
            List<AiMessage> messages, boolean requireJsonObject) {
        JsonArray encoded = new JsonArray();
        if (requireJsonObject) {
            /*
             * 不依赖调用方记得写提示：官方 JSON object 模式在没有明确指令时可能持续输出空白。
             * 该 system 消息由客户端固定生成，不来自模型或玩家输入。
             */
            appendMessage(encoded, AiMessageRole.SYSTEM,
                    JSON_OBJECT_SYSTEM_INSTRUCTION);
        }
        for (AiMessage message : messages) {
            requireUnicodeScalars(message.content());
            if (message.role() == AiMessageRole.TOOL) {
                /* AiMessage 尚未带 tool_call_id；不能静默构造一个 API 语义错误的消息。 */
                throw new DeepSeekProviderException(
                        DeepSeekFailureCode.CAPABILITY_UNAVAILABLE);
            }
            appendMessage(encoded, message.role(), message.content());
        }
        return encoded;
    }

    private static void appendMessage(
            JsonArray target, AiMessageRole role, String content) {
        JsonObject value = new JsonObject();
        value.addProperty("role", switch (role) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> throw new IllegalStateException(
                    "TOOL messages were rejected before encoding");
        });
        value.addProperty("content", content);
        target.add(value);
    }

    private static JsonArray encodeTools(List<DeepSeekToolDefinition> catalog) {
        JsonArray tools = new JsonArray();
        for (DeepSeekToolDefinition tool : catalog) {
            requireUnicodeScalars(tool.definition().toolName());
            requireUnicodeScalars(tool.description());
            validateRuleUnicode(tool.definition().parameters());
            JsonObject function = new JsonObject();
            function.addProperty("name", tool.definition().toolName());
            function.addProperty("description", tool.description());
            function.add("parameters", encodeParameterObject(tool.definition()));
            JsonObject envelope = new JsonObject();
            envelope.addProperty("type", "function");
            envelope.add("function", function);
            tools.add(envelope);
        }
        return tools;
    }

    private static JsonObject encodeParameterObject(ToolDefinition definition) {
        JsonObject schema = new JsonObject();
        schema.addProperty("type", "object");
        schema.addProperty("additionalProperties", false);
        JsonObject properties = new JsonObject();
        JsonArray required = new JsonArray();
        for (Map.Entry<String, ToolParameterRule> entry
                : definition.parameters().entrySet()) {
            properties.add(entry.getKey(), encodeRule(entry.getValue()));
            if (entry.getValue().required()) {
                required.add(entry.getKey());
            }
        }
        schema.add("properties", properties);
        if (required.size() > 0) {
            schema.add("required", required);
        }
        return schema;
    }

    private static JsonObject encodeRule(ToolParameterRule rule) {
        JsonObject schema = new JsonObject();
        switch (rule.type()) {
            case STRING -> {
                schema.addProperty("type", "string");
                schema.addProperty("maxLength", rule.maximumStringCharacters());
                if (rule.stringSemantics()
                        == ToolStringSemantics.RESOURCE_IDENTIFIER) {
                    schema.addProperty("pattern",
                            "^[a-z0-9_.-]{1,64}:[a-z0-9_./-]{1,128}$");
                }
                if (!rule.allowedStringValues().isEmpty()) {
                    JsonArray values = new JsonArray();
                    rule.allowedStringValues().stream().sorted().forEach(values::add);
                    schema.add("enum", values);
                }
            }
            case INTEGER -> {
                schema.addProperty("type", "integer");
                schema.addProperty("minimum",
                        rule.minimumInteger().orElseThrow());
                schema.addProperty("maximum",
                        rule.maximumInteger().orElseThrow());
            }
            case BOOLEAN -> schema.addProperty("type", "boolean");
            case ARRAY -> {
                schema.addProperty("type", "array");
                schema.addProperty("maxItems", rule.maximumCollectionEntries());
                schema.add("items", encodeRule(
                        rule.arrayElementRule().orElseThrow()));
            }
            case OBJECT -> {
                schema.addProperty("type", "object");
                schema.addProperty("additionalProperties", false);
                schema.addProperty("maxProperties", rule.maximumCollectionEntries());
                JsonObject properties = new JsonObject();
                JsonArray required = new JsonArray();
                for (Map.Entry<String, ToolParameterRule> entry
                        : rule.objectFields().entrySet()) {
                    properties.add(entry.getKey(), encodeRule(entry.getValue()));
                    if (entry.getValue().required()) {
                        required.add(entry.getKey());
                    }
                }
                schema.add("properties", properties);
                if (required.size() > 0) {
                    schema.add("required", required);
                }
            }
            case NULL -> throw new DeepSeekProviderException(
                    DeepSeekFailureCode.CAPABILITY_UNAVAILABLE);
        }
        return schema;
    }

    private static void validateRuleUnicode(
            Map<String, ToolParameterRule> fields) {
        for (Map.Entry<String, ToolParameterRule> entry : fields.entrySet()) {
            requireUnicodeScalars(entry.getKey());
            ToolParameterRule rule = entry.getValue();
            for (String allowedValue : rule.allowedStringValues()) {
                requireUnicodeScalars(allowedValue);
            }
            if (rule.type() == ToolValueType.OBJECT) {
                validateRuleUnicode(rule.objectFields());
            } else if (rule.type() == ToolValueType.ARRAY) {
                validateRuleUnicode(Map.of("items",
                        rule.arrayElementRule().orElseThrow()));
            }
        }
    }

    private static void requireUnicodeScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalidRequest();
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalidRequest();
            }
        }
    }

    private static DeepSeekProviderException invalidRequest() {
        return new DeepSeekProviderException(DeepSeekFailureCode.REQUEST_INVALID);
    }
}
