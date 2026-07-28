package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.Objects;
import java.util.UUID;

/**
 * 可追溯到事件或快照的不可变证据引用。
 */
public record EvidenceRef(
        String kind,
        UUID sourceId,
        long sequence,
        String detail) {
    public EvidenceRef {
        kind = requireText(kind, "kind", 32);
        Objects.requireNonNull(sourceId, "sourceId");
        if (sequence < 0) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        detail = requireText(detail, "detail", 128);
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
