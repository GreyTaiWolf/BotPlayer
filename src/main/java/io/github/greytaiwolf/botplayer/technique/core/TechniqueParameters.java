package io.github.greytaiwolf.botplayer.technique.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * Immutable scalar-only parameters.  Techniques never receive live Minecraft
 * objects through their parameter surface.
 */
public record TechniqueParameters(Map<String, Object> values) {
    public static final int MAX_PARAMETERS = 32;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_STRING_LENGTH = 512;
    private static final Pattern NAME =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final TechniqueParameters EMPTY =
            new TechniqueParameters(Map.of());

    public TechniqueParameters {
        Objects.requireNonNull(values, "values");
        if (values.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException("technique parameters exceed "
                    + MAX_PARAMETERS);
        }
        Map<String, Object> sorted = new TreeMap<>();
        values.forEach((name, value) -> {
            requireName(name);
            requireScalar(value);
            sorted.put(name, value);
        });
        values = Collections.unmodifiableMap(new LinkedHashMap<>(sorted));
    }

    public static TechniqueParameters empty() {
        return EMPTY;
    }

    private static void requireName(String value) {
        Objects.requireNonNull(value, "parameter name");
        if (!NAME.matcher(value).matches()) {
            throw new IllegalArgumentException("technique parameter name is invalid");
        }
    }

    private static void requireScalar(Object value) {
        Objects.requireNonNull(value, "parameter value");
        if (value instanceof String string) {
            if (string.length() > MAX_STRING_LENGTH
                    || string.codePoints().anyMatch(Character::isISOControl)
                    || string.codePoints().anyMatch(
                            codePoint -> codePoint >= 0xD800
                                    && codePoint <= 0xDFFF)) {
                throw new IllegalArgumentException(
                        "technique string parameter is not bounded plain text");
            }
            return;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException(
                    "technique decimal parameter must be finite");
        }
        if (!(value instanceof Integer)
                && !(value instanceof Long)
                && !(value instanceof Double)
                && !(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "technique parameter values must be scalar");
        }
    }
}
