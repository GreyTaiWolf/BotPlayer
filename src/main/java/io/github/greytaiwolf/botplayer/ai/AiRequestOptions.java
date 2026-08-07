package io.github.greytaiwolf.botplayer.ai;

import java.util.Objects;
import java.util.Optional;

/**
 * Provider-neutral、受硬上限约束的生成参数。
 */
public record AiRequestOptions(
        int maximumOutputTokens,
        long timeoutMillis,
        AiResponseFormat responseFormat,
        boolean reasoningAllowed,
        boolean toolCallsAllowed,
        Optional<Double> temperature) {
    public static final int MAX_OUTPUT_TOKENS = 131_072;
    public static final long MAX_TIMEOUT_MILLIS = 600_000L;
    public static final double MAX_TEMPERATURE = 2.0D;

    public AiRequestOptions {
        if (maximumOutputTokens < 1
                || maximumOutputTokens > MAX_OUTPUT_TOKENS) {
            throw new IllegalArgumentException(
                    "maximumOutputTokens must be between 1 and "
                            + MAX_OUTPUT_TOKENS);
        }
        if (timeoutMillis < 1L
                || timeoutMillis > MAX_TIMEOUT_MILLIS) {
            throw new IllegalArgumentException(
                    "timeoutMillis must be between 1 and "
                            + MAX_TIMEOUT_MILLIS);
        }
        Objects.requireNonNull(responseFormat, "responseFormat");
        temperature = Objects.requireNonNull(
                temperature, "temperature");
        temperature.ifPresent(value -> {
            if (!Double.isFinite(value)
                    || value < 0.0D
                    || value > MAX_TEMPERATURE) {
                throw new IllegalArgumentException(
                        "temperature must be finite and between 0 and "
                                + MAX_TEMPERATURE);
            }
        });
    }

    public static AiRequestOptions defaults() {
        return new AiRequestOptions(
                4_096,
                60_000L,
                AiResponseFormat.TEXT,
                false,
                false,
                Optional.empty());
    }
}
