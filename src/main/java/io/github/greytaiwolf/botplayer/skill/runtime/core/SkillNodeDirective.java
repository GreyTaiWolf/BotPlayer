package io.github.greytaiwolf.botplayer.skill.runtime.core;

import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import java.util.Objects;
import java.util.Optional;

/**
 * 节点处理器向通用运行时返回的单步、无副作用状态指令。
 */
public record SkillNodeDirective(
        Kind kind,
        Optional<SkillFailureCode> failureCode,
        String safeSummary) {
    public static final int MAX_SUMMARY_LENGTH = 256;

    public SkillNodeDirective {
        kind = Objects.requireNonNull(kind, "kind");
        failureCode = Objects.requireNonNull(
                failureCode, "failureCode");
        boolean requiresFailure = kind == Kind.FAIL;
        if (failureCode.isPresent() != requiresFailure) {
            throw new IllegalArgumentException(
                    "failureCode presence must match fail directive");
        }
        if (failureCode.orElse(SkillFailureCode.NONE)
                == SkillFailureCode.NONE && requiresFailure) {
            throw new IllegalArgumentException(
                    "fail directive requires a concrete failure code");
        }
        safeSummary = requireSummary(safeSummary);
    }

    public static SkillNodeDirective continueRunning(
            String summary) {
        return new SkillNodeDirective(
                Kind.CONTINUE, Optional.empty(), summary);
    }

    public static SkillNodeDirective waitFor(
            Kind kind, String summary) {
        if (!kind.isWaiting()) {
            throw new IllegalArgumentException(
                    "waitFor requires a waiting directive");
        }
        return new SkillNodeDirective(kind, Optional.empty(), summary);
    }

    public static SkillNodeDirective verify(String summary) {
        return new SkillNodeDirective(
                Kind.VERIFY, Optional.empty(), summary);
    }

    public static SkillNodeDirective complete(String summary) {
        return new SkillNodeDirective(
                Kind.COMPLETE, Optional.empty(), summary);
    }

    public static SkillNodeDirective fail(
            SkillFailureCode failureCode, String summary) {
        return new SkillNodeDirective(
                Kind.FAIL,
                Optional.of(Objects.requireNonNull(
                        failureCode, "failureCode")),
                summary);
    }

    public static SkillNodeDirective pause(String summary) {
        return new SkillNodeDirective(
                Kind.PAUSE, Optional.empty(), summary);
    }

    public static SkillNodeDirective preempt(String summary) {
        return new SkillNodeDirective(
                Kind.PREEMPT, Optional.empty(), summary);
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

    public enum Kind {
        CONTINUE,
        WAIT_ACTION,
        WAIT_NAVIGATION,
        WAIT_MENU,
        WAIT_QUERY,
        WAIT_TIMER,
        VERIFY,
        COMPLETE,
        FAIL,
        PAUSE,
        PREEMPT;

        public boolean isWaiting() {
            return switch (this) {
                case WAIT_ACTION,
                        WAIT_NAVIGATION,
                        WAIT_MENU,
                        WAIT_QUERY,
                        WAIT_TIMER -> true;
                case CONTINUE,
                        VERIFY,
                        COMPLETE,
                        FAIL,
                        PAUSE,
                        PREEMPT -> false;
            };
        }
    }
}
