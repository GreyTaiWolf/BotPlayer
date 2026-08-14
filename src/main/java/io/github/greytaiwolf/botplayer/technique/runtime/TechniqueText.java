package io.github.greytaiwolf.botplayer.technique.runtime;

import java.util.Objects;
import java.util.regex.Pattern;

/** Package-private validators for bounded, secret-free runtime diagnostics. */
final class TechniqueText {
    static final int MAX_PHASE_LENGTH = 96;
    static final int MAX_SUMMARY_LENGTH = 256;
    private static final Pattern OPERATION =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    private TechniqueText() {
    }

    static String phase(String value) {
        return plain(Objects.requireNonNull(value, "phase"),
                MAX_PHASE_LENGTH, "phase");
    }

    static String summary(String value) {
        return plain(Objects.requireNonNull(value, "summary"),
                MAX_SUMMARY_LENGTH, "summary");
    }

    static String operation(String value) {
        Objects.requireNonNull(value, "operation");
        if (!OPERATION.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "technique operation key must be a safe identifier");
        }
        return value;
    }

    private static String plain(String value, int maximum, String name) {
        if (value.isEmpty() || value.length() > maximum
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)
                || value.codePoints().anyMatch(codePoint -> codePoint >= 0xD800
                        && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException("technique " + name
                    + " must be bounded plain text");
        }
        return value;
    }
}
