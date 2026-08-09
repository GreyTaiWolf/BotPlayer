package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 不可信包在进入暂存区前得到的有限、无需暴露原始路径和字节的诊断。
 */
public record SkillPackViolation(
        Code code, Optional<UUID> nodeId, String summary) {
    public SkillPackViolation {
        Objects.requireNonNull(code, "code");
        nodeId = Objects.requireNonNull(nodeId, "nodeId");
        Objects.requireNonNull(summary, "summary");
        if (summary.isBlank() || summary.length() > 256) {
            throw new IllegalArgumentException(
                    "summary must contain 1-256 characters");
        }
    }

    public enum Code {
        UNSUPPORTED_SCHEMA,
        SOURCE_TOO_LARGE,
        SOURCE_PATH_MISMATCH,
        DESCRIPTOR_REFERENCE_LIMIT_EXCEEDED,
        DESCRIPTOR_NOT_ALLOWLISTED,
        DESCRIPTOR_NOT_REGISTERED,
        DESCRIPTOR_RISK_EXCEEDED,
        PLAN_NODE_NOT_DECLARED,
        DECLARED_DESCRIPTOR_UNUSED,
        PLAN_INVALID
    }
}
