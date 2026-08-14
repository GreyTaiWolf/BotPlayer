package io.github.greytaiwolf.botplayer.network.payload;

import io.github.greytaiwolf.botplayer.ai.RedactionFilter;
import java.util.Objects;
import java.util.UUID;

/** Package-private bounded-value checks shared by the P6 proposal wire DTOs. */
final class AiProposalPayloadChecks {
    private AiProposalPayloadChecks() {
        throw new AssertionError("No instances");
    }

    static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    static int requireBoundedContent(
            String value, String name, int maximumUtf8Bytes, boolean allowBlank) {
        Objects.requireNonNull(value, name);
        if (!allowBlank && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        int bytes = utf8Length(value, name, maximumUtf8Bytes);
        if (RedactionFilter.redact(value).redactionCount() != 0) {
            throw new IllegalArgumentException(
                    name + " contains credential-like content");
        }
        return bytes;
    }

    static int utf8Length(String value, String name, int maximumUtf8Bytes) {
        Objects.requireNonNull(value, name);
        if (maximumUtf8Bytes < 0) {
            throw new IllegalArgumentException("maximumUtf8Bytes must not be negative");
        }
        int total = 0;
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            int encodedLength;
            if (current <= 0x007F) {
                encodedLength = 1;
            } else if (current <= 0x07FF) {
                encodedLength = 2;
            } else if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length()
                        || !Character.isLowSurrogate(value.charAt(index + 1))) {
                    throw new IllegalArgumentException(
                            name + " must not contain an unpaired surrogate");
                }
                encodedLength = 4;
                index++;
            } else if (Character.isLowSurrogate(current)) {
                throw new IllegalArgumentException(
                        name + " must not contain an unpaired surrogate");
            } else {
                encodedLength = 3;
            }
            if (encodedLength > maximumUtf8Bytes - total) {
                throw new IllegalArgumentException(
                        name + " exceeds maximum UTF-8 bytes "
                                + maximumUtf8Bytes);
            }
            total += encodedLength;
        }
        return total;
    }

    static int addWithinTotal(
            int current, int additional, int maximum, String name) {
        if (additional > maximum - current) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum UTF-8 bytes " + maximum);
        }
        return current + additional;
    }
}
