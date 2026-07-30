package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 手动或上层规划器启动 P5 生存技能时的同步接纳回执。
 */
public record SurvivalSkillSubmission(
        Status status,
        Optional<UUID> runId,
        String safeSummary) {
    public SurvivalSkillSubmission {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if ((status == Status.STARTED) != runId.isPresent()) {
            throw new IllegalArgumentException(
                    "only STARTED submissions carry a runId");
        }
        if (!safeSummary.equals(safeSummary.strip())
                || safeSummary.isEmpty()
                || safeSummary.length()
                        > SurvivalSkillRunView.MAX_SUMMARY_LENGTH
                || safeSummary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a safe non-empty summary");
        }
    }

    public static SurvivalSkillSubmission started(
            UUID runId, String summary) {
        return new SurvivalSkillSubmission(
                Status.STARTED,
                Optional.of(
                        Objects.requireNonNull(runId, "runId")),
                summary);
    }

    public static SurvivalSkillSubmission rejected(
            Status status, String summary) {
        if (status == Status.STARTED) {
            throw new IllegalArgumentException(
                    "rejected submission cannot use STARTED");
        }
        return new SurvivalSkillSubmission(
                status, Optional.empty(), summary);
    }

    public boolean accepted() {
        return status == Status.STARTED;
    }

    public enum Status {
        STARTED,
        BOT_NOT_ACTIVE,
        BOT_BUSY,
        NO_UPGRADE,
        MENU_UNAVAILABLE,
        ACTION_REJECTED,
        RUNTIME_CLOSED
    }
}
