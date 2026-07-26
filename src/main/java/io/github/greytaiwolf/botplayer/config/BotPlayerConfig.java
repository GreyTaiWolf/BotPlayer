package io.github.greytaiwolf.botplayer.config;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class BotPlayerConfig {
    public static final ModConfigSpec SPEC;

    public static final ModConfigSpec.IntValue MAX_BOTS;
    public static final ModConfigSpec.BooleanValue SHOW_IN_PLAYER_LIST;
    public static final ModConfigSpec.BooleanValue AUTO_RESPAWN;
    public static final ModConfigSpec.IntValue RESPAWN_DELAY_TICKS;
    public static final ModConfigSpec.IntValue CHUNK_TRACKING_REFRESH_TICKS;
    public static final ModConfigSpec.IntValue COMMAND_PERMISSION_LEVEL;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("server_player");
        MAX_BOTS = builder
                .comment("Maximum number of BotPlayer instances online at once.")
                .defineInRange("maxBots", 8, 1, 128);
        SHOW_IN_PLAYER_LIST = builder
                .comment("Whether bots appear in the vanilla player list.")
                .define("showInPlayerList", true);
        AUTO_RESPAWN = builder
                .comment("Respawn dead bots through the vanilla respawn path.")
                .define("autoRespawn", true);
        RESPAWN_DELAY_TICKS = builder
                .comment("Delay before a dead bot requests a vanilla respawn.")
                .defineInRange("respawnDelayTicks", 20, 0, 20 * 60);
        CHUNK_TRACKING_REFRESH_TICKS = builder
                .comment("Interval used to refresh vanilla player chunk tracking.")
                .defineInRange("chunkTrackingRefreshTicks", 10, 1, 200);
        builder.pop();

        builder.push("permissions");
        COMMAND_PERMISSION_LEVEL = builder
                .comment("Vanilla permission level required for /botplayer.")
                .defineInRange("commandPermissionLevel", 2, 0, 4);
        builder.pop();

        SPEC = builder.build();
    }

    private BotPlayerConfig() {}
}
