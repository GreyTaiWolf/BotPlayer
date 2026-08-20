package io.github.greytaiwolf.botplayer.building.blueprint;

import java.util.Objects;

/**
 * A bounded block offset relative to a future blueprint anchor.
 *
 * <p>This value deliberately is not a world position: P5D-A0 does not choose a site, load a
 * chunk, or access a Minecraft level.
 */
public record BlueprintOffset(int x, int y, int z)
        implements Comparable<BlueprintOffset> {
    /** Prevents a small blueprint from smuggling a far-away or overflowing coordinate. */
    public static final int MAX_ABSOLUTE_COMPONENT = 64;

    public BlueprintOffset {
        if (exceedsLimit(x) || exceedsLimit(y) || exceedsLimit(z)) {
            throw new IllegalArgumentException(
                    "blueprint offset component exceeds "
                            + MAX_ABSOLUTE_COMPONENT);
        }
    }

    @Override
    public int compareTo(BlueprintOffset other) {
        Objects.requireNonNull(other, "other");
        int xComparison = Integer.compare(x, other.x);
        if (xComparison != 0) {
            return xComparison;
        }
        int yComparison = Integer.compare(y, other.y);
        if (yComparison != 0) {
            return yComparison;
        }
        return Integer.compare(z, other.z);
    }

    private static boolean exceedsLimit(int value) {
        return Math.abs((long) value) > MAX_ABSOLUTE_COMPONENT;
    }
}
