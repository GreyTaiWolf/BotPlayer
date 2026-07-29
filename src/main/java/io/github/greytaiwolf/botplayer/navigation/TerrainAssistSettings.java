package io.github.greytaiwolf.botplayer.navigation;

import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;

/**
 * 服务端 Terrain Assist 上限。请求 policy 只能缩小这些值，不能放大。
 */
public record TerrainAssistSettings(
        boolean allowBreak,
        int maximumBlocksBroken,
        boolean allowPlace,
        int maximumBlocksPlaced) {
    public TerrainAssistSettings {
        requireRange(
                maximumBlocksBroken,
                "maximumBlocksBroken");
        requireRange(
                maximumBlocksPlaced,
                "maximumBlocksPlaced");
    }

    public static TerrainAssistSettings fromConfig() {
        return new TerrainAssistSettings(
                BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_BREAK.get(),
                BotPlayerConfig
                        .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_BROKEN
                        .get(),
                BotPlayerConfig.NAVIGATION_ALLOW_TERRAIN_PLACE.get(),
                BotPlayerConfig
                        .NAVIGATION_MAXIMUM_TERRAIN_BLOCKS_PLACED
                        .get());
    }

    private static void requireRange(int value, String name) {
        if (value < 0 || value > 8) {
            throw new IllegalArgumentException(
                    name + " must be between 0 and 8");
        }
    }
}
