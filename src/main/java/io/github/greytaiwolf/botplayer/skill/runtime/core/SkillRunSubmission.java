package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidation;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * 计划提交结果；被拒绝的计划永远不会绑定信号、占用预约或留下运行记录。
 */
public record SkillRunSubmission(
        Status status,
        Optional<UUID> runId,
        Optional<SkillPlanValidation> validation,
        String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public SkillRunSubmission {
        Objects.requireNonNull(status, "status");
        runId = Objects.requireNonNull(runId, "runId");
        validation = Objects.requireNonNull(validation, "validation");
        safeSummary = requireSummary(safeSummary);
        if (runId.isPresent() != (status == Status.ACCEPTED)) {
            throw new IllegalArgumentException(
                    "runId presence must match accepted status");
        }
        if (validation.isPresent()
                != (status == Status.INVALID_PLAN)) {
            throw new IllegalArgumentException(
                    "validation presence must match invalid plan status");
        }
    }

    static SkillRunSubmission accepted(UUID runId) {
        return new SkillRunSubmission(
                Status.ACCEPTED,
                Optional.of(Objects.requireNonNull(runId, "runId")),
                Optional.empty(),
                "技能计划已接入运行队列");
    }

    public static SkillRunSubmission rejected(
            Status status, String summary) {
        if (status == Status.ACCEPTED
                || status == Status.INVALID_PLAN) {
            throw new IllegalArgumentException(
                    "rejected status must not be accepted or invalid");
        }
        return new SkillRunSubmission(
                status,
                Optional.empty(),
                Optional.empty(),
                summary);
    }

    static SkillRunSubmission invalid(SkillPlanValidation validation) {
        return new SkillRunSubmission(
                Status.INVALID_PLAN,
                Optional.empty(),
                Optional.of(Objects.requireNonNull(
                        validation, "validation")),
                "技能计划未通过静态校验");
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

    public enum Status {
        ACCEPTED,
        INVALID_PLAN,
        BOT_NOT_ACTIVE,
        PLAN_BOT_MISMATCH,
        /** 内建计划 revision 无法安全递增，不能重用旧 checkpoint 身份。 */
        PLAN_REVISION_UNAVAILABLE,
        /** 编译进服务器的固定模板在启动/提交时未能通过自己的静态合同。 */
        INVALID_BUILTIN_PLAN,
        PACK_NOT_APPROVED,
        BOT_BUSY,
        RUNTIME_CAPACITY_EXCEEDED,
        HANDLER_UNAVAILABLE,
        RUN_ID_UNAVAILABLE,
        RUNTIME_CLOSED
    }
}
