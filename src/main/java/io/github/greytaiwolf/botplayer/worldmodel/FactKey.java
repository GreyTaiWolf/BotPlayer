package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.Objects;

/**
 * 同一 key 同时只能有一个 ACTIVE 值，旧值进入可审计历史。
 */
public record FactKey(String namespace, String dimension, String subject) {
    public FactKey {
        namespace = requireText(namespace, "namespace", 64);
        dimension = requireText(dimension, "dimension", 128);
        subject = requireText(subject, "subject", 256);
    }

    private static String requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
        return value;
    }
}
