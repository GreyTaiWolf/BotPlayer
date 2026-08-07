package io.github.greytaiwolf.botplayer.ai;

/**
 * Provider 返回的未验证工具调用。
 *
 * <p>`argumentsJson` 仅是有界文本；P6 ToolCallCodec/ToolFirewall 通过前不得执行。
 */
public record AiRawToolCall(
        String callId,
        String name,
        String argumentsJson) {
    public static final int MAX_CALL_ID_LENGTH = 128;
    public static final int MAX_ARGUMENTS_LENGTH = 65_536;

    public AiRawToolCall {
        callId = AiChecks.opaqueId(
                callId, "callId", MAX_CALL_ID_LENGTH);
        name = AiChecks.toolName(name, "name");
        argumentsJson = AiChecks.boundedText(
                argumentsJson,
                "argumentsJson",
                MAX_ARGUMENTS_LENGTH,
                false);
    }
}
