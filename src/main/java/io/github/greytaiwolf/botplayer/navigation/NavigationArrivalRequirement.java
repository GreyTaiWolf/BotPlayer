package io.github.greytaiwolf.botplayer.navigation;

/**
 * Defines the physical condition required before a navigation session may
 * report arrival after its geometric goal has been reached.
 *
 * <p>{@link #GRID_CELL} retains the legacy navigation contract. {@link
 * #GROUNDED_GRID_CELL} is intentionally opt-in for callers, such as P5A
 * resource acquisition, whose next action relies on the player being stably
 * supported at the destination rather than merely passing through its block
 * cell while jumping.
 */
public enum NavigationArrivalRequirement {
    GRID_CELL,
    GROUNDED_GRID_CELL;

    public boolean isSatisfied(boolean geometricGoalReached, boolean onGround) {
        return geometricGoalReached
                && (this == GRID_CELL || onGround);
    }
}
