package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * 只允许可安全复制、比较和持久化的标量参数。
 */
public record SkillParameters(Map<String, Object> values) {
    public static final int MAX_PARAMETERS = 64;
    public static final int MAX_NAME_LENGTH = 64;
    public static final int MAX_STRING_LENGTH = 1_024;

    private static final Pattern NAME =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");
    private static final SkillParameters EMPTY =
            new SkillParameters(Map.of());

    public SkillParameters {
        Objects.requireNonNull(values, "values");
        if (values.size() > MAX_PARAMETERS) {
            throw new IllegalArgumentException(
                    "parameters exceeds maximum size " + MAX_PARAMETERS);
        }
        Map<String, Object> sorted = new TreeMap<>();
        values.forEach((name, value) -> {
            requireName(name);
            requireValue(value);
            sorted.put(name, value);
        });
        values = Collections.unmodifiableMap(
                new LinkedHashMap<>(sorted));
    }

    public static SkillParameters empty() {
        return EMPTY;
    }

    public Optional<Object> value(String name) {
        requireName(name);
        return Optional.ofNullable(values.get(name));
    }

    static void requireName(String name) {
        Objects.requireNonNull(name, "parameter name");
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException(
                    "parameter name must contain 1-64 safe lower-case characters");
        }
    }

    private static void requireValue(Object value) {
        Objects.requireNonNull(value, "parameter value");
        if (value instanceof String string) {
            if (string.length() > MAX_STRING_LENGTH
                    || string.codePoints()
                            .anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(
                        "string parameter exceeds its safe bound");
            }
            return;
        }
        if (value instanceof Double decimal) {
            if (!Double.isFinite(decimal)) {
                throw new IllegalArgumentException(
                        "decimal parameter must be finite");
            }
            return;
        }
        if (!(value instanceof Integer)
                && !(value instanceof Long)
                && !(value instanceof Boolean)) {
            throw new IllegalArgumentException(
                    "parameter values must be String, Integer, Long, Double, or Boolean");
        }
    }
}
