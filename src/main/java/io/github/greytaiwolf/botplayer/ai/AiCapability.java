package io.github.greytaiwolf.botplayer.ai;

/**
 * Provider/model 可公开探测且不含凭据的能力标记。
 */
public enum AiCapability {
    CHAT,
    TOOL_CALLS,
    JSON_OBJECT,
    JSON_SCHEMA,
    REASONING,
    STREAMING
}
