package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 基于滑动窗口证据的活动推断，不会覆盖原始事件。
 */
public record ActivityHypothesis(
        UUID actorId,
        ActivityType type,
        long startedTick,
        long lastEvidenceTick,
        float confidence,
        ActivityConfidenceBand band,
        List<EvidenceRef> evidence) {
    public ActivityHypothesis {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(type, "type");
        if (startedTick < 0 || lastEvidenceTick < startedTick) {
            throw new IllegalArgumentException("activity tick range is invalid");
        }
        if (!Float.isFinite(confidence) || confidence < 0.0F || confidence > 1.0F) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(band, "band");
        if (band != ActivityConfidenceBand.fromConfidence(confidence)) {
            throw new IllegalArgumentException("band does not match confidence");
        }
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > WorldFact.MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "evidence exceeds maximum size " + WorldFact.MAX_EVIDENCE);
        }
    }

    public static ActivityHypothesis unknown(UUID actorId, long tick) {
        return new ActivityHypothesis(
                actorId,
                ActivityType.UNKNOWN,
                tick,
                tick,
                0.0F,
                ActivityConfidenceBand.UNCERTAIN,
                List.of());
    }
}
