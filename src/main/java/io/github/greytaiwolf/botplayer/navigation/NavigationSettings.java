package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;

public record NavigationSettings(
        int horizontalRadius,
        int verticalRadius,
        int snapshotCellsPerBotTick,
        int snapshotGlobalCellsPerTick,
        int maximumSnapshotTicks,
        int maximumExpansions,
        int maximumConcurrentPlans,
        int maximumQueuedPlans,
        int maximumGoalDistance,
        int followerInputTicks,
        int stuckWindowTicks,
        double waypointTolerance,
        int minimumSprintFood,
        int minimumTravelFood,
        double minimumTravelHealth) {
    public NavigationSettings {
        requireRange(horizontalRadius, 4, 48, "horizontalRadius");
        requireRange(verticalRadius, 2, 16, "verticalRadius");
        requireRange(
                snapshotCellsPerBotTick,
                64,
                8_192,
                "snapshotCellsPerBotTick");
        requireRange(
                snapshotGlobalCellsPerTick,
                snapshotCellsPerBotTick,
                65_536,
                "snapshotGlobalCellsPerTick");
        requireRange(maximumSnapshotTicks, 1, 100, "maximumSnapshotTicks");
        requireRange(maximumExpansions, 100, 250_000, "maximumExpansions");
        requireRange(maximumConcurrentPlans, 1, 8, "maximumConcurrentPlans");
        requireRange(maximumQueuedPlans, 1, 128, "maximumQueuedPlans");
        requireRange(maximumGoalDistance, 16, 16_384, "maximumGoalDistance");
        requireRange(followerInputTicks, 2, 5, "followerInputTicks");
        requireRange(stuckWindowTicks, 5, 40, "stuckWindowTicks");
        if (!Double.isFinite(waypointTolerance)
                || waypointTolerance < 0.1D
                || waypointTolerance > 1.0D) {
            throw new IllegalArgumentException(
                    "waypointTolerance must be finite and between 0.1 and 1.0");
        }
        requireRange(minimumSprintFood, 0, 20, "minimumSprintFood");
        requireRange(minimumTravelFood, 0, 20, "minimumTravelFood");
        if (minimumSprintFood < minimumTravelFood) {
            throw new IllegalArgumentException(
                    "minimumSprintFood must not be lower than minimumTravelFood");
        }
        if (!Double.isFinite(minimumTravelHealth)
                || minimumTravelHealth < 0.0D
                || minimumTravelHealth > 2_048.0D) {
            throw new IllegalArgumentException(
                    "minimumTravelHealth must be finite and between 0 and 2048");
        }
    }

    public static NavigationSettings fromConfig() {
        return new NavigationSettings(
                BotPlayerConfig.NAVIGATION_HORIZONTAL_RADIUS.get(),
                BotPlayerConfig.NAVIGATION_VERTICAL_RADIUS.get(),
                BotPlayerConfig.NAVIGATION_SNAPSHOT_CELLS_PER_BOT_TICK.get(),
                BotPlayerConfig.NAVIGATION_SNAPSHOT_GLOBAL_CELLS_PER_TICK.get(),
                BotPlayerConfig.NAVIGATION_MAXIMUM_SNAPSHOT_TICKS.get(),
                BotPlayerConfig.NAVIGATION_MAXIMUM_EXPANSIONS.get(),
                BotPlayerConfig.NAVIGATION_MAXIMUM_CONCURRENT_PLANS.get(),
                BotPlayerConfig.NAVIGATION_MAXIMUM_QUEUED_PLANS.get(),
                BotPlayerConfig.NAVIGATION_MAXIMUM_GOAL_DISTANCE.get(),
                BotPlayerConfig.NAVIGATION_FOLLOWER_INPUT_TICKS.get(),
                BotPlayerConfig.NAVIGATION_STUCK_WINDOW_TICKS.get(),
                BotPlayerConfig.NAVIGATION_WAYPOINT_TOLERANCE.get(),
                BotPlayerConfig.NAVIGATION_MINIMUM_SPRINT_FOOD.get(),
                BotPlayerConfig.NAVIGATION_MINIMUM_TRAVEL_FOOD.get(),
                BotPlayerConfig.NAVIGATION_MINIMUM_TRAVEL_HEALTH.get());
    }

    private static void requireRange(
            int value, int minimum, int maximum, String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    name + " must be between " + minimum + " and " + maximum);
        }
    }
}
