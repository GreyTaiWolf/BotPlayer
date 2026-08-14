package io.github.greytaiwolf.botplayer.technique.core;

import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueState;
import java.util.Objects;
import java.util.UUID;

/** Immutable, redaction-safe terminal result retained for diagnostics. */
public record TechniqueOutcome(
        UUID techniqueRunId,
        UUID skillRunId,
        UUID botId,
        long botGeneration,
        TechniqueId techniqueId,
        TechniqueVersion techniqueVersion,
        TechniqueState state,
        TechniqueFailureCode failureCode,
        long startedTick,
        long finishedTick,
        long revision,
        String safeSummary) {
    public static final int MAX_SAFE_SUMMARY_LENGTH = 256;

    public TechniqueOutcome {
        requireNonZero(techniqueRunId, "techniqueRunId");
        requireNonZero(skillRunId, "skillRunId");
        requireNonZero(botId, "botId");
        Objects.requireNonNull(techniqueId, "techniqueId");
        Objects.requireNonNull(techniqueVersion, "techniqueVersion");
        Objects.requireNonNull(state, "state");
        if (!state.isTerminal()) {
            throw new IllegalArgumentException(
                    "technique outcome must contain a terminal state");
        }
        Objects.requireNonNull(failureCode, "failureCode");
        if (botGeneration < 1L || startedTick < 0L
                || finishedTick < startedTick || revision < 1L) {
            throw new IllegalArgumentException(
                    "technique outcome identity/timing is invalid");
        }
        if (state == TechniqueState.SUCCEEDED
                && failureCode != TechniqueFailureCode.NONE) {
            throw new IllegalArgumentException(
                    "successful technique outcome must use NONE failure code");
        }
        if (state == TechniqueState.FAILED
                && failureCode == TechniqueFailureCode.NONE) {
            throw new IllegalArgumentException(
                    "failed technique outcome requires a failure code");
        }
        if (state == TechniqueState.CANCELLED
                && failureCode != TechniqueFailureCode.CANCELLED) {
            throw new IllegalArgumentException(
                    "cancelled technique outcome must use CANCELLED");
        }
        if (state == TechniqueState.PREEMPTED
                && failureCode != TechniqueFailureCode.SAFETY_PREEMPTED) {
            throw new IllegalArgumentException(
                    "preempted technique outcome must use SAFETY_PREEMPTED");
        }
        safeSummary = requireSummary(safeSummary);
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "safeSummary");
        if (value.isEmpty() || value.length() > MAX_SAFE_SUMMARY_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)
                || value.codePoints().anyMatch(codePoint -> codePoint >= 0xD800
                        && codePoint <= 0xDFFF)) {
            throw new IllegalArgumentException(
                    "technique safe summary must be bounded plain text");
        }
        return value;
    }

    private static void requireNonZero(UUID value, String name) {
        if (new UUID(0L, 0L).equals(Objects.requireNonNull(value, name))) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }
}
