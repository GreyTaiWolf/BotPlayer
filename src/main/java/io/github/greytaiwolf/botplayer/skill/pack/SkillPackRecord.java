package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 仅保存审核元数据；批准者和 Tick 只在 APPROVED/人工 REJECTED 时存在。
 */
public record SkillPackRecord(
        SkillPackCandidate candidate,
        SkillPackValidation validation,
        SkillPackState state,
        long stateChangedAtTick,
        Optional<UUID> reviewedBy) {
    public SkillPackRecord {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(validation, "validation");
        Objects.requireNonNull(state, "state");
        if (!validation.revision().equals(candidate.revision())) {
            throw new IllegalArgumentException(
                    "validation revision must match candidate");
        }
        if (stateChangedAtTick < 0L) {
            throw new IllegalArgumentException(
                    "stateChangedAtTick must be non-negative");
        }
        reviewedBy = Objects.requireNonNull(reviewedBy, "reviewedBy");
        if (state == SkillPackState.STAGED) {
            if (!validation.valid() || reviewedBy.isPresent()) {
                throw new IllegalArgumentException(
                        "only a valid unreviewed candidate can be staged");
            }
        } else if (state == SkillPackState.APPROVED) {
            if (!validation.valid() || reviewedBy.isEmpty()) {
                throw new IllegalArgumentException(
                        "approved pack requires valid audit and administrator");
            }
            requireNonZero(reviewedBy.orElseThrow());
        } else if (reviewedBy.isPresent()) {
            requireNonZero(reviewedBy.orElseThrow());
        }
    }

    public SkillPackRevision revision() {
        return candidate.revision();
    }

    private static void requireNonZero(UUID value) {
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(
                    "administrator id must not be zero UUID");
        }
    }
}
