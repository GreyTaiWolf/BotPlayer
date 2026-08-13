package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Objects;
import java.util.Optional;

/**
 * 暂存和人工审核操作的明确结果，不以异常伪装预期拒绝。
 */
public record SkillPackTransition(
        Status status, Optional<SkillPackRecord> record) {
    public SkillPackTransition {
        Objects.requireNonNull(status, "status");
        record = Objects.requireNonNull(record, "record");
    }

    public enum Status {
        STAGED,
        REJECTED_BY_VALIDATION,
        ALREADY_STAGED,
        ALREADY_APPROVED,
        ALREADY_REJECTED,
        REVISION_CONTENT_CONFLICT,
        CAPACITY_EXCEEDED,
        APPROVED,
        REJECTED,
        NOT_FOUND,
        REVISION_MISMATCH,
        NOT_STAGED
    }
}
