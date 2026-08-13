package io.github.greytaiwolf.botplayer.ai;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * P6 Provider DTO 共用的有界值检查；不接触凭据或 Minecraft 活动对象。
 */
final class AiChecks {
    private static final Pattern PROVIDER_ID =
            Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern TOOL_NAME =
            Pattern.compile("[A-Za-z0-9_.:/-]{1,128}");

    private AiChecks() {
        throw new AssertionError("No instances");
    }

    static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero");
        }
    }

    static String providerId(String value, String name) {
        return requirePattern(
                value,
                name,
                PROVIDER_ID,
                "1-64 lower-case provider characters");
    }

    static String modelId(String value, String name) {
        return requireToken(value, name, 128);
    }

    static String opaqueId(String value, String name, int maximumLength) {
        return requireToken(value, name, maximumLength);
    }

    static String toolName(String value, String name) {
        return requirePattern(
                value,
                name,
                TOOL_NAME,
                "1-128 tool-name characters");
    }

    static String reasonCode(String value, String name) {
        return AiReasonCode.checkedWireCode(value, name);
    }

    static String boundedText(
            String value,
            String name,
            int maximumLength,
            boolean allowEmpty) {
        Objects.requireNonNull(value, name);
        if (value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " exceeds maximum length " + maximumLength);
        }
        if (!allowEmpty && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        /*
         * Java String may contain isolated UTF-16 surrogate code units.  Letting one through
         * here would make later JSON/UTF-8 boundaries disagree about the bytes that were
         * reviewed.  All callers of this common text guard are model input/output, schemas or
         * diagnostic-safe text, so reject it at construction rather than relying on a later
         * transport encoder to replace it.
         */
        ContextBudget.estimateTextTokens(value);
        return value;
    }

    static Optional<String> optionalText(
            Optional<String> value,
            String name,
            int maximumLength) {
        Objects.requireNonNull(value, name);
        return value.map(text -> boundedText(
                text, name, maximumLength, false));
    }

    static Optional<String> optionalReasonCode(
            Optional<String> value, String name) {
        Objects.requireNonNull(value, name);
        return value.map(code -> reasonCode(code, name));
    }

    static Instant instant(Instant value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBefore(Instant.EPOCH)) {
            throw new IllegalArgumentException(
                    name + " must not be before the Unix epoch");
        }
        return value;
    }

    private static String requirePattern(
            String value,
            String name,
            Pattern pattern,
            String expected) {
        Objects.requireNonNull(value, name);
        if (!pattern.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " must contain " + expected);
        }
        return value;
    }

    private static String requireToken(
            String value, String name, int maximumLength) {
        Objects.requireNonNull(value, name);
        if (value.isEmpty() || value.length() > maximumLength) {
            throw new IllegalArgumentException(
                    name + " must contain 1-" + maximumLength
                            + " characters");
        }
        boolean invalid = value.codePoints().anyMatch(codePoint ->
                Character.isWhitespace(codePoint)
                        || Character.isISOControl(codePoint));
        if (invalid) {
            throw new IllegalArgumentException(
                    name + " must not contain whitespace or control characters");
        }
        ContextBudget.estimateTextTokens(value);
        return value;
    }
}
