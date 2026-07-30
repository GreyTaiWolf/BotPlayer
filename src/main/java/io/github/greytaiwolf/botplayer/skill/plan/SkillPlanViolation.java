package io.github.greytaiwolf.botplayer.skill.plan;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SkillPlanViolation(
        Code code,
        Optional<UUID> nodeId,
        String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public SkillPlanViolation {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (safeSummary.isBlank()
                || safeSummary.length() > MAX_SUMMARY_LENGTH
                || !safeSummary.equals(safeSummary.strip())
                || safeSummary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must contain 1-256 trimmed characters");
        }
    }

    public enum Code {
        EMPTY_PLAN,
        NODE_LIMIT_EXCEEDED,
        EDGE_LIMIT_EXCEEDED,
        DUPLICATE_NODE,
        DUPLICATE_EDGE,
        UNKNOWN_EDGE_NODE,
        SELF_EDGE,
        CYCLE_DETECTED,
        DEPTH_EXCEEDED,
        UNKNOWN_SKILL,
        SKILL_VERSION_MISMATCH,
        INVALID_PARAMETERS
    }
}
