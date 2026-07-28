package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.Objects;

/**
 * 世界 revision 的精确失效范围。
 */
public record RevisionScope(String dimension, RevisionKind kind, String targetId) {
    public RevisionScope {
        dimension = requireText(dimension, "dimension", 128);
        Objects.requireNonNull(kind, "kind");
        targetId = requireText(targetId, "targetId", 256);
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
