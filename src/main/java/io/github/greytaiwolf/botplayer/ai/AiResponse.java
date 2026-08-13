package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Provider 返回的不可变、有界但仍不可信的响应。
 */
public record AiResponse(
        UUID requestId,
        String providerId,
        String model,
        AiFinishReason finishReason,
        String outputText,
        Optional<String> reasoningText,
        Optional<String> structuredOutputJson,
        List<AiRawToolCall> toolCalls,
        AiTokenUsage usage) {
    public static final int MAX_OUTPUT_TEXT_LENGTH = 262_144;
    public static final int MAX_REASONING_TEXT_LENGTH = 262_144;
    public static final int MAX_STRUCTURED_OUTPUT_LENGTH = 262_144;
    public static final int MAX_TOOL_CALLS = 32;
    public static final int MAX_TOTAL_RESPONSE_CHARACTERS = 524_288;

    public AiResponse {
        AiChecks.requireNonZero(requestId, "requestId");
        providerId = AiChecks.providerId(
                providerId, "providerId");
        model = AiChecks.modelId(model, "model");
        Objects.requireNonNull(finishReason, "finishReason");
        outputText = AiChecks.boundedText(
                outputText,
                "outputText",
                MAX_OUTPUT_TEXT_LENGTH,
                true);
        reasoningText = AiChecks.optionalText(
                reasoningText,
                "reasoningText",
                MAX_REASONING_TEXT_LENGTH);
        structuredOutputJson = AiChecks.optionalText(
                structuredOutputJson,
                "structuredOutputJson",
                MAX_STRUCTURED_OUTPUT_LENGTH);
        Objects.requireNonNull(toolCalls, "toolCalls");
        if (toolCalls.size() > MAX_TOOL_CALLS) {
            throw new IllegalArgumentException(
                    "toolCalls exceeds maximum size " + MAX_TOOL_CALLS);
        }
        List<AiRawToolCall> copiedToolCalls =
                new ArrayList<>(toolCalls.size());
        long totalCharacters = outputText.length()
                + reasoningText.map(String::length).orElse(0)
                + structuredOutputJson.map(String::length).orElse(0);
        for (AiRawToolCall toolCall : toolCalls) {
            AiRawToolCall copied = Objects.requireNonNull(
                    toolCall, "toolCall");
            copiedToolCalls.add(copied);
            totalCharacters += copied.callId().length()
                    + copied.name().length()
                    + copied.argumentsJson().length();
            if (totalCharacters > MAX_TOTAL_RESPONSE_CHARACTERS) {
                throw new IllegalArgumentException(
                        "response content exceeds total maximum "
                                + MAX_TOTAL_RESPONSE_CHARACTERS);
            }
        }
        toolCalls = List.copyOf(copiedToolCalls);
        Objects.requireNonNull(usage, "usage");
        if (finishReason == AiFinishReason.TOOL_CALLS
                && toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "TOOL_CALLS responses require at least one tool call");
        }
        if (finishReason != AiFinishReason.TOOL_CALLS
                && !toolCalls.isEmpty()) {
            throw new IllegalArgumentException(
                    "tool calls require TOOL_CALLS finish reason");
        }
    }

    /** Provider 输出、推理链和工具参数均不属于可记录诊断数据。 */
    @Override
    public String toString() {
        return "AiResponse[requestId=" + requestId
                + ", providerId=" + providerId
                + ", model=" + model
                + ", finishReason=" + finishReason
                + ", outputLength=" + outputText.length()
                + ", reasoningLength=" + reasoningText.map(String::length).orElse(0)
                + ", structuredOutputLength="
                + structuredOutputJson.map(String::length).orElse(0)
                + ", toolCallCount=" + toolCalls.size()
                + ", usage=" + usage + "]";
    }
}
