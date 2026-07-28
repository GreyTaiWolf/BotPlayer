package io.github.greytaiwolf.botplayer.worldmodel;

import java.util.List;
import java.util.Objects;

/**
 * 新观察准备提交到世界模型的事实候选。
 */
public record WorldFactDraft(
        FactKey key,
        FactValue value,
        RevisionScope scope,
        RevisionStamp revision,
        long observedTick,
        float confidence,
        FactSource source,
        List<EvidenceRef> evidence,
        FactStatus initialStatus,
        InvalidationRule invalidation) {
    public WorldFactDraft {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(revision, "revision");
        if (observedTick < 0) {
            throw new IllegalArgumentException("observedTick must not be negative");
        }
        if (!Float.isFinite(confidence) || confidence < 0.0F || confidence > 1.0F) {
            throw new IllegalArgumentException("confidence must be between 0 and 1");
        }
        Objects.requireNonNull(source, "source");
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > WorldFact.MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "evidence exceeds maximum size " + WorldFact.MAX_EVIDENCE);
        }
        Objects.requireNonNull(initialStatus, "initialStatus");
        if (initialStatus != FactStatus.ACTIVE
                && initialStatus != FactStatus.UNVERIFIED) {
            throw new IllegalArgumentException(
                    "new facts must start ACTIVE or UNVERIFIED");
        }
        Objects.requireNonNull(invalidation, "invalidation");
    }
}
