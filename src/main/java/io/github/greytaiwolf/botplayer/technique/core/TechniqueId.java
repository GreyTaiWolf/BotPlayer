package io.github.greytaiwolf.botplayer.technique.core;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Stable, Minecraft-free identifier for a short-lived player technique.
 */
public record TechniqueId(String namespace, String path)
        implements Comparable<TechniqueId> {
    public static final int MAX_NAMESPACE_LENGTH = 64;
    public static final int MAX_PATH_LENGTH = 128;

    private static final Pattern NAMESPACE =
            Pattern.compile("[a-z0-9_.-]{1,64}");
    private static final Pattern PATH =
            Pattern.compile("[a-z0-9_./-]{1,128}");

    public TechniqueId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        if (!NAMESPACE.matcher(namespace).matches()
                || !PATH.matcher(path).matches()) {
            throw new IllegalArgumentException(
                    "technique id must use safe lower-case resource characters");
        }
    }

    public static TechniqueId parse(String value) {
        Objects.requireNonNull(value, "value");
        int separator = value.indexOf(':');
        if (separator <= 0 || separator == value.length() - 1
                || separator != value.lastIndexOf(':')) {
            throw new IllegalArgumentException(
                    "technique id must use namespace:path");
        }
        return new TechniqueId(value.substring(0, separator),
                value.substring(separator + 1));
    }

    @Override
    public int compareTo(TechniqueId other) {
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
