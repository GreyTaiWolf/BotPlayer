package io.github.greytaiwolf.botplayer.skill.core;

/**
 * 技能契约使用的有界语义版本。
 */
public record SkillVersion(int major, int minor, int patch)
        implements Comparable<SkillVersion> {
    public static final int MAX_COMPONENT = 65_535;

    public SkillVersion {
        requireComponent(major, "major");
        requireComponent(minor, "minor");
        requireComponent(patch, "patch");
    }

    @Override
    public int compareTo(SkillVersion other) {
        int majorOrder = Integer.compare(major, other.major);
        if (majorOrder != 0) {
            return majorOrder;
        }
        int minorOrder = Integer.compare(minor, other.minor);
        return minorOrder != 0
                ? minorOrder
                : Integer.compare(patch, other.patch);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch;
    }

    private static void requireComponent(int value, String name) {
        if (value < 0 || value > MAX_COMPONENT) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and " + MAX_COMPONENT);
        }
    }
}
