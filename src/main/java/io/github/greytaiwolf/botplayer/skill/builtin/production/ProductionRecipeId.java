package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 一个受 schema 审核的生产配方名称，而不是可执行脚本或服务端 recipe 对象。
 */
public record ProductionRecipeId(String value) {
    public static final int MAX_LENGTH = 80;
    private static final Pattern PATTERN =
            Pattern.compile("[a-z0-9][a-z0-9_.-]{0,79}");

    public ProductionRecipeId {
        Objects.requireNonNull(value, "value");
        if (value.length() > MAX_LENGTH || !PATTERN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "production recipe id must be a bounded lower-case identifier");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
