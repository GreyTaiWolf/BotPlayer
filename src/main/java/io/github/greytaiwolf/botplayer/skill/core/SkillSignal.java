package io.github.greytaiwolf.botplayer.skill.core;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * 异步完成边界只能提交这种不含活动游戏对象的信号。
 */
public record SkillSignal(
        UUID signalId,
        UUID runId,
        UUID botId,
        long botGeneration,
        long runRevision,
        UUID operationId,
        SkillSignalType type,
        SkillSignalStatus status,
        SkillFailureCode failureCode,
        List<ActionEvidence> evidence,
        String safeSummary,
        long gameTick) {
    public static final int MAX_SUMMARY_LENGTH = 256;
    public static final int MAX_EVIDENCE_ITEMS = 16;

    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    public SkillSignal {
        requireNonZero(signalId, "signalId");
        requireNonZero(runId, "runId");
        requireNonZero(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException(
                    "botGeneration must be positive");
        }
        if (runRevision < 0L) {
            throw new IllegalArgumentException(
                    "runRevision must not be negative");
        }
        requireNonZero(operationId, "operationId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(failureCode, "failureCode");
        if (status == SkillSignalStatus.SUCCEEDED
                && failureCode != SkillFailureCode.NONE) {
            throw new IllegalArgumentException(
                    "successful signals must use failureCode NONE");
        }
        if (status != SkillSignalStatus.SUCCEEDED
                && failureCode == SkillFailureCode.NONE) {
            throw new IllegalArgumentException(
                    "non-success signals must include a failure code");
        }
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.size() > MAX_EVIDENCE_ITEMS) {
            throw new IllegalArgumentException(
                    "evidence exceeds 16 items");
        }
        Set<String> evidenceKeys = new HashSet<>();
        for (ActionEvidence item : evidence) {
            Objects.requireNonNull(item, "evidence item");
            if (!evidenceKeys.add(item.key())) {
                throw new IllegalArgumentException(
                        "evidence keys must be unique");
            }
        }
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(safeSummary, "safeSummary");
        if (safeSummary.length() > MAX_SUMMARY_LENGTH
                || !safeSummary.equals(safeSummary.strip())
                || safeSummary.codePoints()
                        .anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "safeSummary must be a trimmed 0-256 character string");
        }
        if (gameTick < 0L) {
            throw new IllegalArgumentException(
                    "gameTick must not be negative");
        }
    }

    static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (ZERO_UUID.equals(value)) {
            throw new IllegalArgumentException(
                    name + " must not be the zero UUID");
        }
    }
}
