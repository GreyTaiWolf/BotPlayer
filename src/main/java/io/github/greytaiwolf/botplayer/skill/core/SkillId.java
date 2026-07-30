package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * 不依赖 Minecraft 注册表类型的稳定技能标识。
 */
public record SkillId(String namespace, String path)
        implements Comparable<SkillId> {
    public static final int MAX_NAMESPACE_LENGTH = 64;
    public static final int MAX_PATH_LENGTH = 128;

    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH =
            Pattern.compile("[a-z0-9_./-]{1,128}");

    public SkillId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "namespace must contain 1-64 lower-case resource characters");
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "path must contain 1-128 lower-case resource characters");
        }
    }

    public static SkillId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0
                || separator == value.length() - 1
                || separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "skill id must use namespace:path");
        }
        return new SkillId(
                value.substring(0, separator),
                value.substring(separator + 1));
    }

    @Override
    public int compareTo(SkillId other) {
        Objects.requireNonNull(other, "other");
        int namespaceOrder = namespace.compareTo(other.namespace);
        return namespaceOrder != 0
                ? namespaceOrder
                : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
