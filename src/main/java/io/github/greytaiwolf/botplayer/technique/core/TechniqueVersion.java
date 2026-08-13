package io.github.greytaiwolf.botplayer.technique.core;

import java.util.Objects;

/** Bounded semantic version of one technique contract. */
public record TechniqueVersion(int major, int minor, int patch)
        implements Comparable<TechniqueVersion> {
    public static final int MAX_COMPONENT = 65_535;

    public TechniqueVersion {
        requireComponent(major, "major");
        requireComponent(minor, "minor");
        requireComponent(patch, "patch");
    }

    @Override
    public int compareTo(TechniqueVersion other) {
        Objects.requireNonNull(other, "other");
        int majorOrder = Integer.compare(major, other.major);
        if (majorOrder != 0) {
            return majorOrder;
        }
        int minorOrder = Integer.compare(minor, other.minor);
        return minorOrder != 0 ? minorOrder
                : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    private static void requireComponent(int value, String name) {
        if (value < 0 || value > MAX_COMPONENT) {
            throw new IllegalArgumentException(name + " must be between 0 and "
                    + MAX_COMPONENT);
        }
    }
}
