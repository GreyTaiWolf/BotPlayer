package io.github.greytaiwolf.botplayer.ai;

import java.time.Duration;
import java.util.Objects;

/**
 * {@link ScriptedAiProvider} 的单次确定性结果。
 */
public sealed interface ScriptedAiOutcome
        permits ScriptedAiOutcome.Response,
        ScriptedAiOutcome.Failure,
        ScriptedAiOutcome.Delayed {
    Duration MAX_DELAY = Duration.ofMinutes(5L);

    /**
     * 成功返回的固定响应。
     */
    record Response(AiResponse response) implements ScriptedAiOutcome {
        public Response {
            Objects.requireNonNull(response, "response");
        }
    }

    /**
     * 已脱敏、可用于重试测试的固定失败。
     */
    record Failure(AiProviderException exception) implements ScriptedAiOutcome {
        public Failure {
            Objects.requireNonNull(exception, "exception");
        }
    }

    /**
     * 需要显式 scheduler 才会完成的单次延迟结果。
     *
     * <p>内层只能是直接成功或直接失败，禁止递归延迟以保证单次调用只排入一个有界任务。
     */
    record Delayed(Duration delay, ScriptedAiOutcome outcome)
            implements ScriptedAiOutcome {
        public Delayed {
            Objects.requireNonNull(delay, "delay");
            if (delay.isNegative() || delay.compareTo(MAX_DELAY) > 0) {
                throw new IllegalArgumentException(
                        "delay must be between zero and " + MAX_DELAY);
            }
            Objects.requireNonNull(outcome, "outcome");
            if (outcome instanceof Delayed) {
                throw new IllegalArgumentException(
                        "nested delayed outcomes are not supported");
            }
        }
    }
}
