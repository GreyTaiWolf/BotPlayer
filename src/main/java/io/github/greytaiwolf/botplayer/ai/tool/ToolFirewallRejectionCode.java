package io.github.greytaiwolf.botplayer.ai.tool;

/** 静态 Firewall 的稳定、无敏感原文拒绝码。 */
public enum ToolFirewallRejectionCode {
    CALL_COUNT_EXCEEDED,
    TOOL_NOT_WHITELISTED,
    TOOL_NOT_REGISTERED,
    RISK_TOO_HIGH,
    FORBIDDEN_PARAMETER_NAME,
    SCRIPT_LIKE_VALUE,
    UNKNOWN_PARAMETER,
    MISSING_PARAMETER,
    TYPE_MISMATCH,
    INTEGER_OUT_OF_RANGE,
    STRING_TOO_LONG,
    COLLECTION_TOO_LARGE,
    STRING_VALUE_NOT_ALLOWED
}
