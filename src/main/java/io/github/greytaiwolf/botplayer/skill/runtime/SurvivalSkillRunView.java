package io.github.greytaiwolf.botplayer.skill.runtime;

import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 管理命令和测试可读取的有界技能运行视图。
 */
public record SurvivalSkillRunView(
        UUID runId,
        UUID botId,
        long botGeneration,
        SurvivalSkillKind kind,
        SkillRunState state,
        long stateRevision,
        long startedTick,
        long updatedTick,
        long deadlineTick,
        int operationSequence,
        Optional<SkillFailureCode> failureCode,
        String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public SurvivalSkillRunView {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L
                || stateRevision < 0L
                || startedTick < 0L
                || updatedTick < startedTick
                || deadlineTick <= startedTick
                || operationSequence < 0) {
            throw new IllegalArgumentException(
                    "skill run view counters are invalid");
        }
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(failureCode, "failureCode");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (safeSummary.length() > MAX_SUMMARY_LENGTH
                || !safeSummary.equals(safeSummary.strip())
                || safeSummary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed 0-256 character string");
        }
        if (state == SkillRunState.FAILED
                && failureCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "failed skill views require a failure code");
        }
        if (state != SkillRunState.FAILED
                && failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "only failed skill views may expose a failure code");
        }
    }
}
