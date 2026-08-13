package io.github.greytaiwolf.botplayer.skill.checkpoint;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** 重启后重新观察得到的纯证据；旧观察不能替代它。 */
public record SkillCheckpointReobservation(
        boolean complete,
        Optional<String> observedScopeFingerprint,
        List<CheckpointEvidence> evidence) {
    public static final int MAX_EVIDENCE = SkillCheckpoint.MAX_EVIDENCE;

    public SkillCheckpointReobservation {
        observedScopeFingerprint = Objects.requireNonNull(
                observedScopeFingerprint, "observedScopeFingerprint");
        observedScopeFingerprint.ifPresent(value ->
                CheckpointNbt.requireSha256(
                        value, "observedScopeFingerprint"));
        evidence = List.copyOf(Objects.requireNonNull(evidence, "evidence"));
        if (evidence.size() > MAX_EVIDENCE) {
            throw new IllegalArgumentException(
                    "reobservation evidence exceeds maximum " + MAX_EVIDENCE);
        }
        if (complete && evidence.isEmpty()) {
            throw new IllegalArgumentException(
                    "complete reobservation requires evidence");
        }
    }
}
