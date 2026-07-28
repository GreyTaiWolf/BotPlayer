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
    public static final ModConfigSpec.IntValue ACTION_MAILBOX_CAPACITY;
    public static final ModConfigSpec.IntValue ACTION_LEDGER_CAPACITY;
    public static final ModConfigSpec.IntValue ACTION_COMMANDS_PER_TICK;
    public static final ModConfigSpec.IntValue ACTION_ACTIVE_CAPACITY;
    public static final ModConfigSpec.IntValue ACTION_COMPLETION_CAPACITY;
    public static final ModConfigSpec.DoubleValue INVENTORY_VIEW_DISTANCE;

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

        builder.push("actions");
        ACTION_MAILBOX_CAPACITY = builder
                .comment("Bounded number of action and cancellation commands awaiting the server thread.")
                .defineInRange("mailboxCapacity", 1024, 2, 65_536);
        ACTION_LEDGER_CAPACITY = builder
                .comment("Bounded idempotency ledger entries retained by the action runtime.")
                .defineInRange("ledgerCapacity", 4096, 1, 65_536);
        ACTION_COMMANDS_PER_TICK = builder
                .comment("Maximum action mailbox commands processed in one server tick.")
                .defineInRange("commandsPerTick", 128, 1, 4096);
        ACTION_ACTIVE_CAPACITY = builder
                .comment("Maximum actions that may be active across all BotPlayers.")
                .defineInRange("activeCapacity", 512, 1, 16_384);
        ACTION_COMPLETION_CAPACITY = builder
                .comment("Bounded completion notifications waiting for the callback dispatcher.")
                .defineInRange("completionCapacity", 2048, 2, 65_536);
        builder.pop();

        builder.push("inventory");
        INVENTORY_VIEW_DISTANCE = builder
                .comment("Maximum same-dimension distance at which a player may edit a bot inventory.")
                .defineInRange("viewDistance", 8.0D, 1.0D, 64.0D);
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
