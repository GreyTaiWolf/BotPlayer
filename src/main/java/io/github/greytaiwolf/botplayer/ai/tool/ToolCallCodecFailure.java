package io.github.greytaiwolf.botplayer.ai.tool;

/** 不含原始模型文本的稳定 Codec 拒绝原因。 */
public enum ToolCallCodecFailure {
    INVALID_RESPONSE,
    MALFORMED_JSON,
    ROOT_NOT_OBJECT,
    TYPE_NOT_SUPPORTED,
    UNKNOWN_FIELD,
    DUPLICATE_FIELD,
    INVALID_FIELD_NAME,
    INVALID_STRING,
    INVALID_INTEGER,
    LIMIT_EXCEEDED,
    MISSING_REQUIRED_FIELD
}
