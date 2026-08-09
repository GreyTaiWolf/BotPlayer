package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 运行时对管理命令、checkpoint 和测试开放的纯数据快照。
 */
public record SkillRunView(
        UUID runId,
        UUID botId,
        long botGeneration,
        UUID planId,
        long planRevision,
        SkillRunState state,
        long stateRevision,
        Optional<UUID> activeNodeId,
        Optional<SkillId> activeSkillId,
        int completedNodes,
        int totalNodes,
        long startedTick,
        long updatedTick,
        long deadlineTick,
        Optional<SkillFailureCode> failureCode,
        String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public SkillRunView {
        requireNonZero(runId, "runId");
        requireNonZero(botId, "botId");
        requireNonZero(planId, "planId");
        if (botGeneration <= 0L
                || planRevision <= 0L
                || stateRevision < 0L
                || completedNodes < 0
                || totalNodes < 1
                || completedNodes > totalNodes
                || startedTick < 0L
                || updatedTick < startedTick
                || deadlineTick <= startedTick) {
            throw new IllegalArgumentException(
                    "skill run view counters are invalid");
        }
        state = Objects.requireNonNull(state, "state");
        activeNodeId = Objects.requireNonNull(
                activeNodeId, "activeNodeId");
        activeSkillId = Objects.requireNonNull(
                activeSkillId, "activeSkillId");
        if (activeNodeId.isPresent() != activeSkillId.isPresent()) {
            throw new IllegalArgumentException(
                    "active node and skill must be present together");
        }
        if (state.isTerminal()
                && (activeNodeId.isPresent()
                        || activeSkillId.isPresent())) {
            throw new IllegalArgumentException(
                    "terminal run views must not expose an active node");
        }
        failureCode = Objects.requireNonNull(
                failureCode, "failureCode");
        if (state == SkillRunState.FAILED
                && failureCode.isEmpty()) {
            throw new IllegalArgumentException(
                    "failed run views require a failure code");
        }
        if (state != SkillRunState.FAILED
                && failureCode.isPresent()) {
            throw new IllegalArgumentException(
                    "only failed run views may expose a failure code");
        }
        safeSummary = requireSummary(safeSummary);
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (new UUID(0L, 0L).equals(value)) {
            throw new IllegalArgumentException(
                    name + " must not be the zero UUID");
        }
    }

    private static String requireSummary(String value) {
        Objects.requireNonNull(value, "safeSummary");
        if (value.length() > MAX_SUMMARY_LENGTH
                || !value.equals(value.strip())
                || value.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed 0-256 character string");
        }
        return value;
    }
}
