package io.github.greytaiwolf.botplayer.skill.task;

import java.util.Objects;
import java.util.UUID;

/**
 * 查询结果绑定的运行身份；generation 或 revision 不一致时必须失效。
 */
public record TaskSensorRunIdentity(
        UUID botId, long generation, UUID skillRunId, long runRevision) {
    public TaskSensorRunIdentity {
        requireNonZero(botId, "botId");
        if (generation < 1L) {
            throw new IllegalArgumentException(
                    "generation must be positive");
        }
        requireNonZero(skillRunId, "skillRunId");
        if (runRevision < 1L) {
            throw new IllegalArgumentException(
                    "runRevision must be positive");
        }
    }

    private static void requireNonZero(UUID value, String name) {
        Objects.requireNonNull(value, name);
        if (value.getMostSignificantBits() == 0L
                && value.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be zero UUID");
        }
    }
}
