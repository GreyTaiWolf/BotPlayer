package io.github.greytaiwolf.botplayer.skill.pack;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * JAR 外声明式技能包的稳定标识；路径不允许目录或平台相关分隔符。
 */
public record SkillPackId(String namespace, String path)
        implements Comparable<SkillPackId> {
    public static final int MAX_NAMESPACE_LENGTH = 64;
    public static final int MAX_PATH_LENGTH = 64;

    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH =
            Pattern.compile("[a-z0-9_-]{1,64}");

    public SkillPackId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()) {
            throw new IllegalArgumentException(
                    "namespace must contain 1-64 safe resource characters");
        }
        if (!PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "path must contain 1-64 safe pack characters");
        }
    }

    public static SkillPackId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0
                || separator == value.length() - 1
                || separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "skill pack id must use namespace:path");
        }
        return new SkillPackId(
                value.substring(0, separator),
                value.substring(separator + 1));
    }

    public String expectedSourcePath() {
        return "data/"
                + namespace
                + "/botplayer/skills/"
                + path
                + ".json";
    }

    @Override
    public int compareTo(SkillPackId other) {
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
