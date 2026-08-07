package io.github.greytaiwolf.botplayer.ai;

/**
 * 请求 Provider 返回的最高层格式；后续 ToolCallCodec 仍会重新解析和校验。
 */
public enum AiResponseFormat {
    TEXT,
    JSON_OBJECT,
    JSON_SCHEMA
}
