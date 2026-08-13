package io.github.greytaiwolf.botplayer.ai.plan;

import java.util.Objects;
import java.util.UUID;

/** Package-private validation shared by the P6-to-P5 contract DTOs. */
final class AiPlanChecks {
    private AiPlanChecks() {
        throw new AssertionError("No instances");
    }

    static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    static String boundedPlainText(String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > maximumLength) {
            throw new IllegalArgumentException(name + " must contain between 1 and "
                    + maximumLength + " characters");
        }
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isISOControl(current)) {
                throw new IllegalArgumentException(name + " must not contain controls");
            }
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            name + " must not contain an unpaired surrogate");
                }
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        name + " must not contain an unpaired surrogate");
            }
        }
        return value;
    }
}
