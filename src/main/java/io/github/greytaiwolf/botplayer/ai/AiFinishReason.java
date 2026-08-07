package io.github.greytaiwolf.botplayer.ai;

/**
 * 归一化后的 Provider 完成原因。
 */
public enum AiFinishReason {
    STOP,
    LENGTH,
    TOOL_CALLS,
    CONTENT_FILTER,
    CANCELLED,
    UNKNOWN
}
