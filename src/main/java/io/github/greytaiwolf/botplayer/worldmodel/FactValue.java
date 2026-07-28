package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.Map;
import java.util.Objects;

/**
 * 有界、可回放的事实值；不允许嵌入 Minecraft 活动对象。
 */
public record FactValue(Map<String, String> fields) {
    public static final int MAX_FIELDS = 32;

    public FactValue {
        Objects.requireNonNull(fields, "fields");
        if (fields.isEmpty() || fields.size() > MAX_FIELDS) {
            throw new IllegalArgumentException(
                    "fields must contain 1-" + MAX_FIELDS + " entries");
        }
        fields.forEach((key, value) -> {
            requireText(key, "field key", 64);
            requireText(value, "field value", 256);
        });
        fields = Map.copyOf(fields);
    }

    public static FactValue of(String key, String value) {
        return new FactValue(Map.of(key, value));
    }

    private static void requireText(String value, String field, int maximumLength) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    field + " must contain 1-" + maximumLength + " characters");
        }
    }
}
