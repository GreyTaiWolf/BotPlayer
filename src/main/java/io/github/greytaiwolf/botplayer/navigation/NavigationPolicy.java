package io.github.greytaiwolf.botplayer.navigation;

public record NavigationPolicy(
        boolean allowSprint,
        boolean allowSwim,
        boolean allowClimb,
        boolean allowOpenWoodenDoor,
        boolean closeDoorAfterPass,
        boolean allowBreak,
        int maximumBlocksBroken,
        boolean allowPlace,
        int maximumBlocksPlaced,
        int maximumSafeDrop,
        int minimumFoodToContinue,
        float minimumHealthToContinue,
        int maximumReplans,
        int maximumRecoveryAttempts) {
    public NavigationPolicy {
        requireRange(maximumBlocksBroken, 0, 8, "maximumBlocksBroken");
        requireRange(maximumBlocksPlaced, 0, 8, "maximumBlocksPlaced");
        requireRange(maximumSafeDrop, 0, 4, "maximumSafeDrop");
        requireRange(minimumFoodToContinue, 0, 20, "minimumFoodToContinue");
        if (!Float.isFinite(minimumHealthToContinue)
                || minimumHealthToContinue < 0.0F
                || minimumHealthToContinue > 2048.0F) {
            throw new IllegalArgumentException(
                    "minimumHealthToContinue must be finite and between 0 and 2048");
        }
        requireRange(maximumReplans, 0, 64, "maximumReplans");
        requireRange(maximumRecoveryAttempts, 0, 16, "maximumRecoveryAttempts");
        if (!allowBreak && maximumBlocksBroken != 0) {
            throw new IllegalArgumentException(
                    "maximumBlocksBroken must be zero when breaking is disabled");
        }
        if (!allowPlace && maximumBlocksPlaced != 0) {
            throw new IllegalArgumentException(
                    "maximumBlocksPlaced must be zero when placing is disabled");
        }
    }

    public static NavigationPolicy safeDefault() {
        return new NavigationPolicy(
                true,
                true,
                true,
                true,
                false,
                false,
                0,
                false,
                0,
                3,
                5,
                6.0F,
                16,
                5);
    }

    public NavigationPolicy withTerrainAssist(
            boolean breakAllowed,
            int blocksBroken,
            boolean placeAllowed,
            int blocksPlaced) {
        return new NavigationPolicy(
                allowSprint,
                allowSwim,
                allowClimb,
                allowOpenWoodenDoor,
                closeDoorAfterPass,
                breakAllowed,
                blocksBroken,
                placeAllowed,
                blocksPlaced,
                maximumSafeDrop,
                minimumFoodToContinue,
                minimumHealthToContinue,
                maximumReplans,
                maximumRecoveryAttempts);
    }

    /**
     * Retains every traversal, safety, and recovery bound while changing only whether the
     * follower may issue sprint input. Callers that must settle on a one-block-wide exact goal
     * can opt into this narrower physical contract without weakening the general policy.
     */
    public NavigationPolicy withSprint(boolean sprintAllowed) {
        return new NavigationPolicy(
                sprintAllowed,
                allowSwim,
                allowClimb,
                allowOpenWoodenDoor,
                closeDoorAfterPass,
                allowBreak,
                maximumBlocksBroken,
                allowPlace,
                maximumBlocksPlaced,
                maximumSafeDrop,
                minimumFoodToContinue,
                minimumHealthToContinue,
                maximumReplans,
                maximumRecoveryAttempts);
    }

    private static void requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}
