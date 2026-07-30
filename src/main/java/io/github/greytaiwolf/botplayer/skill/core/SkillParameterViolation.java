package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Objects;

public record SkillParameterViolation(
        String field, Code code, String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public SkillParameterViolation {
        SkillParameters.requireName(field);
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (safeSummary.length() > MAX_SUMMARY_LENGTH
                || !safeSummary.equals(safeSummary.strip())
                || safeSummary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed 0-256 character string");
        }
    }

    public enum Code {
        UNKNOWN_PARAMETER,
        MISSING_REQUIRED,
        TYPE_MISMATCH,
        OUT_OF_RANGE,
        TOO_SHORT,
        TOO_LONG,
        VALUE_NOT_ALLOWED
    }
}
