package io.github.greytaiwolf.botplayer.skill.task;

import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 感知证据只携带可复制标量，不得夹带 Entity、ItemStack、Menu 或世界对象。
 */
public record TaskSensorEvidence(String kind, SkillParameters fields) {
    public static final int MAX_KIND_LENGTH = 64;

    private static final Pattern KIND =
            Pattern.compile("[a-z][a-z0-9_.-]{0,63}");

    public TaskSensorEvidence {
        Objects.requireNonNull(kind, "kind");
        if (!KIND.matcher(kind).matches()) {
            throw new IllegalArgumentException(
                    "kind must contain 1-64 safe lower-case characters");
        }
        Objects.requireNonNull(fields, "fields");
    }
}
