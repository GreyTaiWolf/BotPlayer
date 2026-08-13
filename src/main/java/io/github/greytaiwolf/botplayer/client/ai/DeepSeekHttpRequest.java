package io.github.greytaiwolf.botplayer.client.ai;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.Objects;

/**
 * 仅限客户端 HTTP executor 使用的受控请求。
 *
 * <p>它刻意不使用 record，避免自动 {@code toString()} 把 secret 变成日志或崩溃报告内容。
 * 请求完成后必须调用 {@link #clearSecret()}。</p>
 */
public final class DeepSeekHttpRequest {
    public static final int MAX_BODY_BYTES = 1_048_576;
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10L);

    private final URI endpoint;
    private final DeepSeekHttpMethod method;
    private final String body;
    private final Duration timeout;
    private final boolean stream;
    private final char[] secret;

    DeepSeekHttpRequest(
            URI endpoint,
            DeepSeekHttpMethod method,
            String body,
            Duration timeout,
            boolean stream,
            char[] secret) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.method = Objects.requireNonNull(method, "method");
        this.body = Objects.requireNonNull(body, "body");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.stream = stream;
        requireTrustedEndpoint(this.endpoint, this.method);
        if (this.timeout.isZero() || this.timeout.isNegative()
                || this.timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("DeepSeek request timeout is outside bounds");
        }
        requireUtf8BodyBound(this.body);
        this.secret = Objects.requireNonNull(secret, "secret").clone();
        if (this.secret.length == 0) {
            throw new IllegalArgumentException("DeepSeek secret must not be empty");
        }
    }

    public URI endpoint() {
        return endpoint;
    }

    DeepSeekHttpMethod method() {
        return method;
    }

    public String body() {
        return body;
    }

    public Duration timeout() {
        return timeout;
    }

    public boolean stream() {
        return stream;
    }

    char[] copySecret() {
        return secret.clone();
    }

    void clearSecret() {
        Arrays.fill(secret, '\0');
    }

    static void requireUtf8BodyBound(String value) {
        int bytes = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int encodedBytes;
            if (current < 0x80) {
                encodedBytes = 1;
            } else if (current < 0x800) {
                encodedBytes = 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            "DeepSeek request body contains an unpaired surrogate");
                }
                encodedBytes = 4;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        "DeepSeek request body contains an unpaired surrogate");
            } else {
                encodedBytes = 3;
            }
            if (encodedBytes > MAX_BODY_BYTES - bytes) {
                throw new IllegalArgumentException(
                        "DeepSeek request body exceeds maximum UTF-8 size");
            }
            bytes += encodedBytes;
        }
    }

    private static void requireTrustedEndpoint(
            URI endpoint, DeepSeekHttpMethod method) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                || !"api.deepseek.com".equalsIgnoreCase(endpoint.getHost())
                || (endpoint.getPort() != -1 && endpoint.getPort() != 443)
                || endpoint.getRawUserInfo() != null
                || endpoint.getRawQuery() != null
                || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("DeepSeek endpoint is not trusted");
        }
        boolean expectedPath = switch (method) {
            case GET -> "/models".equals(endpoint.getRawPath());
            case POST -> "/chat/completions".equals(endpoint.getRawPath());
        };
        if (!expectedPath) {
            throw new IllegalArgumentException("DeepSeek endpoint path is not trusted");
        }
    }

    @Override
    public String toString() {
        return "DeepSeekHttpRequest[endpoint=" + endpoint
                + ", method=" + method
                + ", bodyLength=" + body.length()
                + ", timeout=" + timeout
                + ", stream=" + stream
                + ", secret=[REDACTED]]";
    }
}
