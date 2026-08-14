package io.github.greytaiwolf.botplayer.client.ai;

import java.util.Objects;
import java.util.Optional;

/** 客户端 HTTP executor 回交给 Provider 的有界原始响应；不得写入日志或网络 payload。 */
public record DeepSeekHttpResponse(
        int statusCode, String contentType, String body, Optional<String> retryAfterHeader) {
    public static final int MAX_BODY_BYTES = DeepSeekHttpRequest.MAX_BODY_BYTES;
    private static final int MAX_CONTENT_TYPE_LENGTH = 256;
    private static final int MAX_RETRY_AFTER_LENGTH = 128;

    public DeepSeekHttpResponse {
        if (statusCode < 100 || statusCode > 599) {
            throw new IllegalArgumentException("HTTP status code is outside the valid range");
        }
        contentType = requireHeaderValue(
                contentType, "contentType", MAX_CONTENT_TYPE_LENGTH);
        body = Objects.requireNonNull(body, "body");
        retryAfterHeader = Objects.requireNonNull(
                retryAfterHeader, "retryAfterHeader").map(value ->
                        requireHeaderValue(
                                value, "retryAfterHeader", MAX_RETRY_AFTER_LENGTH));
        DeepSeekHttpRequest.requireUtf8BodyBound(body);
    }

    private static String requireHeaderValue(
            String value, String name, int maximumLength) {
        String checked = Objects.requireNonNull(value, name);
        if (checked.length() > maximumLength) {
            throw new IllegalArgumentException(name + " exceeds maximum length");
        }
        for (int index = 0; index < checked.length(); index++) {
            char current = checked.charAt(index);
            if (current < 0x20 || current > 0x7e) {
                throw new IllegalArgumentException(name + " contains a control character");
            }
        }
        return checked;
    }

    @Override
    public String toString() {
        return "DeepSeekHttpResponse[statusCode=" + statusCode
                + ", contentTypePresent=" + !contentType.isEmpty()
                + ", bodyLength=" + body.length()
                + ", retryAfterPresent=" + retryAfterHeader.isPresent() + "]";
    }
}
