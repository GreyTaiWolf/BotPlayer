package io.github.greytaiwolf.botplayer.perception;

import java.util.Map;
import java.util.Objects;

public record BlockObservation(
        String dimension,
        int x,
        int y,
        int z,
        String blockId,
        Map<String, String> properties,
        boolean lineOfSight,
        long observedTick) {
    public BlockObservation {
        dimension = requireText(dimension, "dimension", 128);
        blockId = requireText(blockId, "blockId", 128);
        properties = Map.copyOf(Objects.requireNonNull(properties, "properties"));
        if (properties.size() > 32) {
            throw new IllegalArgumentException("properties exceeds maximum size 32");
        }
        properties.forEach((key, value) -> {
            requireText(key, "property key", 64);
            requireText(value, "property value", 128);
        });
        if (observedTick < 0) {
            throw new IllegalArgumentException("observedTick must not be negative");
        }
    }

    public String targetId() {
        return x + "," + y + "," + z;
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
