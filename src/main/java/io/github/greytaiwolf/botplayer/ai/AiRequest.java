package io.github.greytaiwolf.botplayer.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 交给 Provider 的最小不可变请求。
 *
 * <p>身份、owner、generation、权限和世界 revision 由后续 RequestScheduler/会话层绑定，
 * 不由模型或本 DTO 授权。
 */
public record AiRequest(
        UUID requestId,
        String model,
        List<AiMessage> messages,
        AiRequestOptions options,
        Optional<String> responseSchemaJson) {
    public static final int MAX_MESSAGES = 128;
    public static final int MAX_TOTAL_MESSAGE_CHARACTERS = 262_144;
    public static final int MAX_RESPONSE_SCHEMA_LENGTH = 131_072;

    public AiRequest {
        AiChecks.requireNonZero(requestId, "requestId");
        model = AiChecks.modelId(model, "model");
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty() || messages.size() > MAX_MESSAGES) {
            throw new IllegalArgumentException(
                    "messages must contain between 1 and "
                            + MAX_MESSAGES + " entries");
        }
        List<AiMessage> copiedMessages =
                new ArrayList<>(messages.size());
        long totalCharacters = 0L;
        for (AiMessage message : messages) {
            AiMessage copied = Objects.requireNonNull(
                    message, "message");
            copiedMessages.add(copied);
            totalCharacters += copied.content().length();
            if (totalCharacters > MAX_TOTAL_MESSAGE_CHARACTERS) {
                throw new IllegalArgumentException(
                        "message content exceeds total maximum "
                                + MAX_TOTAL_MESSAGE_CHARACTERS);
            }
        }
        messages = List.copyOf(copiedMessages);
        Objects.requireNonNull(options, "options");
        responseSchemaJson = AiChecks.optionalText(
                responseSchemaJson,
                "responseSchemaJson",
                MAX_RESPONSE_SCHEMA_LENGTH);
        if (options.responseFormat() == AiResponseFormat.JSON_SCHEMA
                && responseSchemaJson.isEmpty()) {
            throw new IllegalArgumentException(
                    "JSON_SCHEMA requests require responseSchemaJson");
        }
        if (options.responseFormat() != AiResponseFormat.JSON_SCHEMA
                && responseSchemaJson.isPresent()) {
            throw new IllegalArgumentException(
                    "responseSchemaJson is only valid for JSON_SCHEMA requests");
        }
    }

    /** 不把 prompt 或 schema 正文写入日志、崩溃报告或异常消息。 */
    @Override
    public String toString() {
        int messageCharacters = messages.stream()
                .mapToInt(message -> message.content().length())
                .sum();
        return "AiRequest[requestId=" + requestId
                + ", model=" + model
                + ", messageCount=" + messages.size()
                + ", messageCharacters=" + messageCharacters
                + ", options=" + options
                + ", responseSchemaPresent=" + responseSchemaJson.isPresent()
                + ", responseSchemaLength="
                + responseSchemaJson.map(String::length).orElse(0) + "]";
    }
}
