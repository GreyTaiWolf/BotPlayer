package io.github.greytaiwolf.botplayer.ai;

/**
 * 可进入异常、健康快照和诊断的固定安全失败码。
 *
 * <p>这里刻意不用调用方传入的字符串。Provider 原始错误码、异常正文、URL、响应体和任何
 * 凭据都不能转换成此枚举，从类型层阻止它们进入普通日志或状态 UI。
 */
public enum AiReasonCode {
    CANCELLED("cancelled"),
    INVALID_REQUEST("invalid_request"),
    AUTHENTICATION_FAILED("authentication_failed"),
    RATE_LIMITED("rate_limited"),
    QUOTA_EXHAUSTED("quota_exhausted"),
    TIMEOUT("timeout"),
    UNAVAILABLE("unavailable"),
    MALFORMED_RESPONSE("malformed_response"),
    OVERLOADED("overloaded"),
    CIRCUIT_OPEN("circuit_open"),
    CIRCUIT_HALF_OPEN("circuit_half_open"),
    TRANSIENT_FAILURE("transient_failure"),
    UNKNOWN("unknown"),
    SCHEDULER_REQUIRED("scheduler_required"),
    SCHEDULER_REJECTED("scheduler_rejected"),
    STALE_LEASE("stale_lease"),
    DEADLINE_OUT_OF_RANGE("deadline_out_of_range"),
    CALLBACK_ATTACHMENT_FAILED("callback_attachment_failed"),
    DELEGATE_HEALTH_MISMATCH("delegate_health_mismatch"),
    DELEGATE_HEALTH_FAILURE("delegate_health_failure"),
    SCRIPT_MISSING("script_missing"),
    SCRIPT_EXHAUSTED("script_exhausted"),
    CREDENTIAL_UNAVAILABLE("credential_unavailable"),
    INVALID_PROVIDER_RESPONSE("invalid_provider_response"),
    RESPONSE_TOO_LARGE("response_too_large"),
    REMOTE_UNAVAILABLE("remote_unavailable"),
    REMOTE_REJECTED("remote_rejected"),
    NETWORK_FAILURE("network_failure"),
    REQUEST_TIMEOUT("request_timeout");

    private final String wireCode;

    AiReasonCode(String wireCode) {
        this.wireCode = wireCode;
    }

    /**
     * 供既有 {@link ProviderHealth} DTO 使用的稳定非敏感字符串。
     */
    public String wireCode() {
        return wireCode;
    }

    /**
     * 校验既有字符串 DTO 边界上的代码，并归一化为本枚举的稳定值。
     *
     * <p>{@link ProviderHealth} 目前为了兼容已发布的 DTO 仍暴露字符串，但只能接收这里列出的
     * 值；任何 Provider 原文或 key 样式文本都会被拒绝。
     */
    static String checkedWireCode(String value, String name) {
        if (value == null) {
            throw new NullPointerException(name);
        }
        for (AiReasonCode reasonCode : values()) {
            if (reasonCode.wireCode.equals(value)) {
                return reasonCode.wireCode;
            }
        }
        throw new IllegalArgumentException(
                name + " must be a fixed AI reason code");
    }
}
