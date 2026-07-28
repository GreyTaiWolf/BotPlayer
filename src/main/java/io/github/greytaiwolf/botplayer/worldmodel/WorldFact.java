package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 带来源、revision、证据和状态的世界事实。
 */
public record WorldFact(
        UUID factId,
        FactKey key,
        FactValue value,
        RevisionScope scope,
        RevisionStamp revision,
        long firstObservedTick,
        long lastConfirmedTick,
        float confidence,
        FactSource source,
        List<EvidenceRef> evidence,
        FactStatus status,
        InvalidationRule invalidation) {
    public static final int MAX_EVIDENCE = 16;

    public WorldFact {
        Objects.requireNonNull(factId, "factId");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(revision, "revision");
        if (firstObservedTick < 0 || lastConfirmedTick < firstObservedTick) {
            throw new IllegalArgumentException("fact tick range is invalid");
        }
        if (!Float.isFinite(confidence) || confidence < 0.0F || confidence > 1.0F) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(evidence, "evidence");
        if (evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "evidence exceeds maximum size " + MAX_EVIDENCE);
        }
        evidence = List.copyOf(evidence);
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(invalidation, "invalidation");
    }
}
