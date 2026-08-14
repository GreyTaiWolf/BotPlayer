package io.github.greytaiwolf.botplayer.client.ai;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import io.github.greytaiwolf.botplayer.ai.AiFinishReason;
import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import io.github.greytaiwolf.botplayer.ai.AiRequest;
import io.github.greytaiwolf.botplayer.ai.AiResponse;
import io.github.greytaiwolf.botplayer.ai.AiResponseFormat;
import io.github.greytaiwolf.botplayer.ai.AiTokenUsage;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/** DeepSeek JSON/SSE 响应到有界、仍不可信 {@link AiResponse} 的严格解码。 */
final class DeepSeekResponseCodec {
    private static final String PROVIDER_ID = "deepseek";
    private static final Pattern NON_NEGATIVE_INTEGER = Pattern.compile("0|[1-9][0-9]*");
    private static final int MAX_EMBEDDED_JSON_DEPTH = 64;

    AiResponse decodeJson(
            AiRequest request, DeepSeekProviderConfig config, String responseBody) {
        try {
            String checkedBody = Objects.requireNonNull(responseBody, "responseBody");
            DeepSeekJsonStructureGuard.requireDocument(checkedBody);
            JsonObject root = requireObject(JsonParser.parseString(checkedBody));
            String model = requireString(root, "model");
            requireExpectedModel(request, config, model);
            JsonArray choices = requireArray(root, "choices");
            if (choices.size() != 1) {
                throw invalid();
            }
            JsonObject choice = requireObject(choices.get(0));
            requireZeroIndex(choice);
            AiFinishReason reason = parseFinishReason(nullableString(choice,
                    "finish_reason").orElseThrow(DeepSeekResponseCodec::invalid));
            JsonObject message = requireObjectField(choice, "message");
            if (!"assistant".equals(requireString(message, "role"))) {
                throw invalid();
            }
            String output = nullableString(message, "content").orElse("");
            Optional<String> reasoning = nullableString(message, "reasoning_content");
            List<AiRawToolCall> toolCalls = decodeToolCalls(
                    request, config, optionalArray(message, "tool_calls"));
            AiTokenUsage usage = decodeUsage(optionalObject(root, "usage"));
            return response(request, output, reasoning, toolCalls, reason, usage);
        } catch (DeepSeekProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    AiResponse decodeSse(
            AiRequest request, DeepSeekProviderConfig config, String responseBody) {
        try {
            SseAccumulator accumulator = new SseAccumulator(request, config);
            StringBuilder data = new StringBuilder();
            boolean done = false;
            int cursor = 0;
            while (cursor < responseBody.length()) {
                int lineEnd = responseBody.indexOf('\n', cursor);
                int end = lineEnd < 0 ? responseBody.length() : lineEnd;
                int contentEnd = end > cursor && responseBody.charAt(end - 1) == '\r'
                        ? end - 1 : end;
                String line = responseBody.substring(cursor, contentEnd);
                cursor = lineEnd < 0 ? responseBody.length() : lineEnd + 1;
                if (line.isEmpty()) {
                    done = acceptSseData(accumulator, data, done);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    int valueStart = 5;
                    if (line.length() > valueStart && line.charAt(valueStart) == ' ') {
                        valueStart++;
                    }
                    data.append(line, valueStart, line.length());
                }
                /* event/id/retry 等 SSE 元数据不含权限语义，故不参与模型 DTO。 */
            }
            done = acceptSseData(accumulator, data, done);
            if (!done) {
                throw invalid();
            }
            return accumulator.finish();
        } catch (DeepSeekProviderException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid();
        }
    }

    private static boolean acceptSseData(
            SseAccumulator accumulator, StringBuilder data, boolean done) {
        if (data.length() == 0) {
            return done;
        }
        String payload = data.toString();
        data.setLength(0);
        if ("[DONE]".equals(payload)) {
            if (done) {
                throw invalid();
            }
            return true;
        }
        if (done) {
            throw invalid();
        }
        DeepSeekJsonStructureGuard.requireDocument(payload);
        accumulator.accept(requireObject(JsonParser.parseString(payload)));
        return false;
    }

    private AiResponse response(
            AiRequest request,
            String output,
            Optional<String> reasoning,
            List<AiRawToolCall> toolCalls,
            AiFinishReason reason,
            AiTokenUsage usage) {
        requireMaximumLength(output, AiResponse.MAX_OUTPUT_TEXT_LENGTH);
        reasoning.ifPresent(value -> requireMaximumLength(value,
                AiResponse.MAX_REASONING_TEXT_LENGTH));
        Optional<String> structured = Optional.empty();
        if (request.options().responseFormat() == AiResponseFormat.JSON_OBJECT
                && reason == AiFinishReason.TOOL_CALLS) {
            if (!output.isBlank()) {
                throw invalid();
            }
        } else if (request.options().responseFormat() == AiResponseFormat.JSON_OBJECT) {
            if (output.isBlank()) {
                throw invalid();
            }
            DeepSeekJsonStructureGuard.requireDocument(output);
            JsonElement parsed = JsonParser.parseString(output);
            if (!parsed.isJsonObject()) {
                throw invalid();
            }
            requireEmbeddedJsonUnicode(parsed, 0);
            structured = Optional.of(output);
        }
        return new AiResponse(
                request.requestId(), PROVIDER_ID, request.model(), reason, output,
                reasoning, structured, toolCalls, usage);
    }

    private static List<AiRawToolCall> decodeToolCalls(
            AiRequest request,
            DeepSeekProviderConfig config,
            Optional<JsonArray> encodedCalls) {
        if (encodedCalls.isEmpty()) {
            return List.of();
        }
        if (!request.options().toolCallsAllowed()) {
            throw invalid();
        }
        JsonArray source = encodedCalls.orElseThrow();
        if (source.size() > AiResponse.MAX_TOOL_CALLS) {
            throw invalid();
        }
        List<AiRawToolCall> result = new ArrayList<>(source.size());
        for (JsonElement element : source) {
            JsonObject call = requireObject(element);
            if (!"function".equals(requireString(call, "type"))) {
                throw invalid();
            }
            JsonObject function = requireObjectField(call, "function");
            String name = requireString(function, "name");
            if (!config.containsTool(name)) {
                throw invalid();
            }
            String arguments = requireString(function, "arguments");
            requireMaximumLength(arguments, AiRawToolCall.MAX_ARGUMENTS_LENGTH);
            requireToolArgumentsUnicode(arguments);
            result.add(new AiRawToolCall(requireString(call, "id"), name, arguments));
        }
        return List.copyOf(result);
    }

    private static AiTokenUsage decodeUsage(Optional<JsonObject> encodedUsage) {
        if (encodedUsage.isEmpty()) {
            return AiTokenUsage.empty();
        }
        JsonObject usage = encodedUsage.orElseThrow();
        long input = optionalNonNegativeLong(usage, "prompt_tokens").orElse(0L);
        long output = optionalNonNegativeLong(usage, "completion_tokens").orElse(0L);
        long cached = optionalNonNegativeLong(usage,
                "prompt_cache_hit_tokens").orElse(0L);
        return new AiTokenUsage(input, output, cached);
    }

    private static void requireExpectedModel(
            AiRequest request, DeepSeekProviderConfig config, String model) {
        if (!request.model().equals(model) || config.findModel(model).isEmpty()) {
            throw invalid();
        }
    }

    private static AiFinishReason parseFinishReason(String value) {
        return switch (value) {
            case "stop" -> AiFinishReason.STOP;
            case "length" -> AiFinishReason.LENGTH;
            case "tool_calls" -> AiFinishReason.TOOL_CALLS;
            case "content_filter" -> AiFinishReason.CONTENT_FILTER;
            case "insufficient_system_resource" -> throw new DeepSeekProviderException(
                    DeepSeekFailureCode.REMOTE_UNAVAILABLE);
            default -> throw invalid();
        };
    }

    private static JsonObject requireObject(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            throw invalid();
        }
        return value.getAsJsonObject();
    }

    private static JsonObject requireObjectField(JsonObject object, String name) {
        return requireObject(required(object, name));
    }

    private static Optional<JsonObject> optionalObject(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value instanceof JsonNull || value.isJsonNull()) {
            return Optional.empty();
        }
        return Optional.of(requireObject(value));
    }

    private static JsonArray requireArray(JsonObject object, String name) {
        JsonElement value = required(object, name);
        if (!value.isJsonArray()) {
            throw invalid();
        }
        return value.getAsJsonArray();
    }

    private static Optional<JsonArray> optionalArray(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value instanceof JsonNull || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonArray()) {
            throw invalid();
        }
        return Optional.of(value.getAsJsonArray());
    }

    private static String requireString(JsonObject object, String name) {
        return nullableString(object, name).orElseThrow(DeepSeekResponseCodec::invalid);
    }

    private static Optional<String> nullableString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value instanceof JsonNull || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive()) {
            throw invalid();
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalid();
        }
        return Optional.of(requireUnicodeScalars(primitive.getAsString()));
    }

    private static Optional<Long> optionalNonNegativeLong(
            JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value instanceof JsonNull || value.isJsonNull()) {
            return Optional.empty();
        }
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid();
        }
        String literal = value.getAsString();
        if (!NON_NEGATIVE_INTEGER.matcher(literal).matches()) {
            throw invalid();
        }
        try {
            return Optional.of(Long.parseLong(literal));
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static void requireZeroIndex(JsonObject choice) {
        if (optionalNonNegativeLong(choice, "index").orElse(-1L) != 0L) {
            throw invalid();
        }
    }

    private static JsonElement required(JsonObject object, String name) {
        JsonElement value = object.get(name);
        if (value == null || value instanceof JsonNull || value.isJsonNull()) {
            throw invalid();
        }
        return value;
    }

    private static DeepSeekProviderException invalid() {
        return new DeepSeekProviderException(
                DeepSeekFailureCode.INVALID_PROVIDER_RESPONSE);
    }

    /** JSON 的 Unicode 转义可表达孤立 surrogate，DTO 绝不能把它当作普通文本继续传递。 */
    private static String requireUnicodeScalars(String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw invalid();
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw invalid();
            }
        }
        return value;
    }

    private static void requireToolArgumentsUnicode(String arguments) {
        DeepSeekJsonStructureGuard.requireDocument(arguments);
        JsonElement parsed = JsonParser.parseString(arguments);
        if (!parsed.isJsonObject()) {
            throw invalid();
        }
        requireEmbeddedJsonUnicode(parsed, 0);
    }

    private static void requireMaximumLength(String value, int maximum) {
        if (value.length() > maximum) {
            throw invalid();
        }
    }

    /** 检查嵌套 JSON 的对象键和字符串值，不能让 escaped surrogate 跨越 DTO 边界。 */
    private static void requireEmbeddedJsonUnicode(
            JsonElement value, int depth) {
        if (depth > MAX_EMBEDDED_JSON_DEPTH) {
            throw invalid();
        }
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry
                    : value.getAsJsonObject().entrySet()) {
                requireUnicodeScalars(entry.getKey());
                requireEmbeddedJsonUnicode(entry.getValue(), depth + 1);
            }
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                requireEmbeddedJsonUnicode(element, depth + 1);
            }
        } else if (value.isJsonPrimitive()
                && value.getAsJsonPrimitive().isString()) {
            requireUnicodeScalars(value.getAsString());
        }
    }

    /** 聚合 text/event-stream 的纯 DTO 字段，不接触 Minecraft 或执行任何工具。 */
    private final class SseAccumulator {
        private final AiRequest request;
        private final DeepSeekProviderConfig config;
        private final StringBuilder output = new StringBuilder();
        private final StringBuilder reasoning = new StringBuilder();
        private final Map<Integer, PartialToolCall> toolCalls = new LinkedHashMap<>();
        private String model;
        private AiFinishReason finishReason;
        private AiTokenUsage usage = AiTokenUsage.empty();

        private SseAccumulator(AiRequest request, DeepSeekProviderConfig config) {
            this.request = Objects.requireNonNull(request, "request");
            this.config = Objects.requireNonNull(config, "config");
        }

        private void accept(JsonObject event) {
            Optional<String> eventModel = nullableString(event, "model");
            if (eventModel.isPresent()) {
                if (model == null) {
                    model = eventModel.orElseThrow();
                    requireExpectedModel(request, config, model);
                } else if (!model.equals(eventModel.orElseThrow())) {
                    throw invalid();
                }
            }
            Optional<JsonObject> eventUsage = optionalObject(event, "usage");
            if (eventUsage.isPresent()) {
                usage = decodeUsage(eventUsage);
            }
            JsonArray choices = requireArray(event, "choices");
            if (choices.size() == 0) {
                return;
            }
            if (finishReason != null) {
                // finish_reason 之后只允许可选 usage 的空 choices event，不能让后续 delta
                // 悄悄改写已完成的模型输出或工具调用。
                throw invalid();
            }
            if (choices.size() != 1) {
                throw invalid();
            }
            JsonObject choice = requireObject(choices.get(0));
            requireZeroIndex(choice);
            Optional<String> finish = nullableString(choice, "finish_reason");
            if (finish.isPresent()) {
                if (finishReason != null) {
                    throw invalid();
                }
                finishReason = parseFinishReason(finish.orElseThrow());
            }
            JsonObject delta = requireObjectField(choice, "delta");
            Optional<String> role = nullableString(delta, "role");
            if (role.isPresent() && !"assistant".equals(role.orElseThrow())) {
                throw invalid();
            }
            nullableString(delta, "content").ifPresent(value -> appendBounded(
                    output, value, AiResponse.MAX_OUTPUT_TEXT_LENGTH));
            nullableString(delta, "reasoning_content").ifPresent(value -> appendBounded(
                    reasoning, value, AiResponse.MAX_REASONING_TEXT_LENGTH));
            optionalArray(delta, "tool_calls").ifPresent(this::acceptToolDeltas);
        }

        private void acceptToolDeltas(JsonArray deltas) {
            if (!request.options().toolCallsAllowed()
                    || deltas.size() > AiResponse.MAX_TOOL_CALLS) {
                throw invalid();
            }
            for (JsonElement element : deltas) {
                JsonObject delta = requireObject(element);
                int index = toToolIndex(required(delta, "index"));
                PartialToolCall call = toolCalls.computeIfAbsent(index,
                        ignored -> new PartialToolCall());
                Optional<String> type = nullableString(delta, "type");
                if (type.isPresent()) {
                    if (!"function".equals(type.orElseThrow())) {
                        throw invalid();
                    }
                    call.markFunctionType();
                }
                nullableString(delta, "id").ifPresent(call::setId);
                Optional<JsonObject> function = optionalObject(delta, "function");
                if (function.isPresent()) {
                    nullableString(function.orElseThrow(), "name")
                            .ifPresent(call::setName);
                    nullableString(function.orElseThrow(), "arguments")
                            .ifPresent(call::appendArguments);
                }
            }
        }

        private AiResponse finish() {
            if (model == null || finishReason == null) {
                throw invalid();
            }
            List<AiRawToolCall> completeCalls = toolCalls.entrySet().stream()
                    .sorted(Comparator.comparingInt(entry -> entry.getKey()))
                    .map(entry -> entry.getValue().complete(config))
                    .toList();
            for (int index = 0; index < completeCalls.size(); index++) {
                if (!toolCalls.containsKey(index)) {
                    throw invalid();
                }
            }
            return response(request, output.toString(),
                    reasoning.length() == 0 ? Optional.empty()
                            : Optional.of(reasoning.toString()),
                    completeCalls, finishReason, usage);
        }
    }

    private static int toToolIndex(JsonElement value) {
        if (!value.isJsonPrimitive()
                || !value.getAsJsonPrimitive().isNumber()) {
            throw invalid();
        }
        String literal = value.getAsString();
        if (!NON_NEGATIVE_INTEGER.matcher(literal).matches()) {
            throw invalid();
        }
        try {
            int index = Integer.parseInt(literal);
            if (index < 0 || index >= AiResponse.MAX_TOOL_CALLS) {
                throw invalid();
            }
            return index;
        } catch (NumberFormatException exception) {
            throw invalid();
        }
    }

    private static void appendBounded(StringBuilder target, String value, int maximum) {
        if (value.length() > maximum - target.length()) {
            throw invalid();
        }
        target.append(value);
    }

    private static final class PartialToolCall {
        private String id;
        private String name;
        private boolean functionType;
        private final StringBuilder arguments = new StringBuilder();

        private void setId(String value) {
            if (id != null && !id.equals(value)) {
                throw invalid();
            }
            id = value;
        }

        private void setName(String value) {
            if (name == null) {
                if (value.length()
                        > DeepSeekToolDefinition.MAX_FUNCTION_NAME_CHARACTERS) {
                    throw invalid();
                }
                name = value;
                return;
            }
            if (name.length() > DeepSeekToolDefinition.MAX_FUNCTION_NAME_CHARACTERS
                    - value.length()) {
                throw invalid();
            }
            name += value;
        }

        private void markFunctionType() {
            functionType = true;
        }

        private void appendArguments(String value) {
            appendBounded(arguments, value, AiRawToolCall.MAX_ARGUMENTS_LENGTH);
        }

        private AiRawToolCall complete(DeepSeekProviderConfig config) {
            if (!functionType || id == null || name == null
                    || !config.containsTool(name)) {
                throw invalid();
            }
            String completeArguments = arguments.toString();
            requireToolArgumentsUnicode(completeArguments);
            return new AiRawToolCall(id, name, completeArguments);
        }
    }
}
