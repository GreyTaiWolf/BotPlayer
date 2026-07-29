package io.github.greytaiwolf.botplayer.navigation.path;

public record TraversalCell(
        boolean known,
        boolean bodyClear,
        boolean stableSupport,
        boolean water,
        boolean climbable,
        boolean openableDoor,
        boolean hazardous) {
    public static final TraversalCell UNKNOWN =
            new TraversalCell(false, false, false, false, false, false, true);

    public boolean traversable() {
        return known
                && bodyClear
                && !hazardous
                && (stableSupport || water || climbable);
    }

    public LocomotionMode locomotionMode() {
        if (climbable) {
            return LocomotionMode.CLIMB;
        }
        return water ? LocomotionMode.WATER : LocomotionMode.GROUND;
    }
}
