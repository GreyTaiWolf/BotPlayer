package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.ai.AiRawToolCall;
import java.util.Objects;

/**
 * C2S AI 提案中的一项未验证工具调用。
 *
 * <p>该对象没有执行语义。服务端仍必须使用 {@code ToolCallCodec} 与 {@code ToolFirewall}
 * 重新解析、限制和审核 {@link #argumentsJson()}。
 */
public record AiProposalToolCallPayload(
        String callId,
        String name,
        String argumentsJson) {
    public static final int MAX_CALL_ID_CHARACTERS = AiRawToolCall.MAX_CALL_ID_LENGTH;
    public static final int MAX_NAME_CHARACTERS = 128;
    /** Wire codec writes these fields with the same byte ceiling as the character identifier cap. */
    public static final int MAX_CALL_ID_UTF8_BYTES = MAX_CALL_ID_CHARACTERS;
    public static final int MAX_NAME_UTF8_BYTES = MAX_NAME_CHARACTERS;
    public static final int MAX_ARGUMENTS_UTF8_BYTES = 16_384;

    public AiProposalToolCallPayload {
        AiRawToolCall raw = new AiRawToolCall(
                Objects.requireNonNull(callId, "callId"),
                Objects.requireNonNull(name, "name"),
                Objects.requireNonNull(argumentsJson, "argumentsJson"));
        callId = raw.callId();
        name = raw.name();
        argumentsJson = raw.argumentsJson();
        if (callId.length() > MAX_CALL_ID_CHARACTERS) {
            throw new IllegalArgumentException("callId exceeds maximum length");
        }
        if (name.length() > MAX_NAME_CHARACTERS) {
            throw new IllegalArgumentException("name exceeds maximum length");
        }
        AiProposalPayloadChecks.requireBoundedContent(
                callId,
                "callId",
                MAX_CALL_ID_UTF8_BYTES,
                false);
        AiProposalPayloadChecks.requireBoundedContent(
                name,
                "name",
                MAX_NAME_UTF8_BYTES,
                false);
        AiProposalPayloadChecks.requireBoundedContent(
                argumentsJson,
                "argumentsJson",
                MAX_ARGUMENTS_UTF8_BYTES,
                false);
    }

    /** Returns a still-untrusted core DTO for the server-only syntax codec. */
    public AiRawToolCall toRawToolCall() {
        return new AiRawToolCall(callId, name, argumentsJson);
    }

    /** 不输出 call ID、工具名或 arguments JSON。 */
    @Override
    public String toString() {
        return "AiProposalToolCallPayload[argumentsUtf8Bytes="
                + AiProposalPayloadChecks.utf8Length(
                        argumentsJson,
                        "argumentsJson",
                        MAX_ARGUMENTS_UTF8_BYTES)
                + "]";
    }
}
