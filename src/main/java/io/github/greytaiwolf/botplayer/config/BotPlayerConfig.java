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
    public static final ModConfigSpec.DoubleValue PERCEPTION_VISUAL_RANGE;
    public static final ModConfigSpec.DoubleValue PERCEPTION_ENTITY_RADIUS;
    public static final ModConfigSpec.IntValue PERCEPTION_LOCAL_BLOCK_RADIUS;
    public static final ModConfigSpec.DoubleValue PERCEPTION_HEARING_RANGE;
    public static final ModConfigSpec.IntValue PERCEPTION_ENTITY_READS_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_BLOCK_READS_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_RAYCASTS_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_EVENT_READS_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_INVENTORY_READS_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_GLOBAL_WORK_PER_TICK;
    public static final ModConfigSpec.IntValue PERCEPTION_AUTHORITY_EVENT_CAPACITY;
    public static final ModConfigSpec.IntValue PERCEPTION_EVENT_CAPACITY_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_PENDING_EVENT_CAPACITY;
    public static final ModConfigSpec.IntValue PERCEPTION_FACT_CAPACITY_PER_BOT;
    public static final ModConfigSpec.IntValue PERCEPTION_REVISION_SCOPE_CAPACITY;
    public static final ModConfigSpec.IntValue PERCEPTION_RECENT_EVENT_LIMIT;
    public static final ModConfigSpec.IntValue PERCEPTION_ACTIVITY_WINDOW_TICKS;
    public static final ModConfigSpec.DoubleValue PERCEPTION_DEGRADE_MSPT;
    public static final ModConfigSpec.DoubleValue PERCEPTION_CRITICAL_MSPT;
    public static final ModConfigSpec.DoubleValue PERCEPTION_RECOVER_MSPT;
    public static final ModConfigSpec.DoubleValue PERCEPTION_CRITICAL_RECOVER_MSPT;

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

        builder.push("perception");
        PERCEPTION_VISUAL_RANGE = builder
                .comment("凝视与视觉语义事件在已加载世界中的最大范围。")
                .defineInRange("visualRange", 24.0D, 4.0D, 64.0D);
        PERCEPTION_ENTITY_RADIUS = builder
                .comment("在已加载世界中有界采样附近实体的最大半径。")
                .defineInRange("entityRadius", 24.0D, 4.0D, 64.0D);
        PERCEPTION_LOCAL_BLOCK_RADIUS = builder
                .comment("在脚边已加载区域中有界采样方块的半径。")
                .defineInRange("localBlockRadius", 1, 0, 2);
        PERCEPTION_HEARING_RANGE = builder
                .comment("普通可听语义事件的回退范围；原版定向声音封包仍是权威边界。")
                .defineInRange("hearingRange", 32.0D, 4.0D, 128.0D);
        PERCEPTION_ENTITY_READS_PER_BOT = builder
                .comment("单个 bot 在普通感知 Tick 中最多读取的实体候选数。")
                .defineInRange("entityReadsPerBot", 64, 1, 512);
        PERCEPTION_BLOCK_READS_PER_BOT = builder
                .comment("单个 bot 在普通感知 Tick 中最多读取的已加载方块位置数。")
                .defineInRange("blockReadsPerBot", 96, 1, 1024);
        PERCEPTION_RAYCASTS_PER_BOT = builder
                .comment("单个 bot 在普通感知 Tick 中最多执行的视线射线检测数。")
                .defineInRange("raycastsPerBot", 24, 1, 256);
        PERCEPTION_EVENT_READS_PER_BOT = builder
                .comment("单个 bot 每 Tick 各自最多扫描的权威或已感知事件候选数。")
                .defineInRange("eventReadsPerBot", 128, 1, 1024);
        PERCEPTION_INVENTORY_READS_PER_BOT = builder
                .comment("单个 bot 每次背包采样最多汇总的槽位数；不得少于完整玩家背包的 41 槽。")
                .defineInRange("inventoryReadsPerBot", 64, 41, 512);
        PERCEPTION_GLOBAL_WORK_PER_TICK = builder
                .comment("一次感知 Tick 内所有 bot 共享的工作单元上限。")
                .defineInRange("globalWorkPerTick", 4096, 64, 65_536);
        PERCEPTION_AUTHORITY_EVENT_CAPACITY = builder
                .comment("运行期有界权威事件环容量。")
                .defineInRange("authorityEventCapacity", 4096, 64, 65_536);
        PERCEPTION_EVENT_CAPACITY_PER_BOT = builder
                .comment("每个 bot generation 的有界已感知事件环容量。")
                .defineInRange("eventCapacityPerBot", 512, 32, 8192);
        PERCEPTION_PENDING_EVENT_CAPACITY = builder
                .comment("可取消事件候选的有界事后状态复核队列容量。")
                .defineInRange("pendingEventCapacity", 2048, 32, 8192);
        PERCEPTION_FACT_CAPACITY_PER_BOT = builder
                .comment("每个 bot 保留的有界短期世界事实容量。")
                .defineInRange("factCapacityPerBot", 2048, 64, 65_536);
        PERCEPTION_REVISION_SCOPE_CAPACITY = builder
                .comment("每个服务器保留的有界精确方块、实体与容器 revision scope 容量。")
                .defineInRange("revisionScopeCapacity", 8192, 64, 65_536);
        PERCEPTION_RECENT_EVENT_LIMIT = builder
                .comment("单个不可变快照中嵌入的近期已感知事件上限。")
                .defineInRange("recentEventLimit", 64, 8, 512);
        PERCEPTION_ACTIVITY_WINDOW_TICKS = builder
                .comment("确定性玩家活动推断使用的滑动事件窗口 Tick 数。")
                .defineInRange("activityWindowTicks", 400, 20, 12_000);
        PERCEPTION_DEGRADE_MSPT = builder
                .comment("非关键传感器开始降级的平滑 MSPT 阈值。")
                .defineInRange("degradeMspt", 45.0D, 5.0D, 200.0D);
        PERCEPTION_CRITICAL_MSPT = builder
                .comment("仅关键感知保持高频的平滑 MSPT 阈值。")
                .defineInRange("criticalMspt", 50.0D, 5.0D, 200.0D);
        PERCEPTION_RECOVER_MSPT = builder
                .comment("从降级感知恢复到普通状态所需的平滑 MSPT。")
                .defineInRange("recoverMspt", 38.0D, 1.0D, 199.0D);
        PERCEPTION_CRITICAL_RECOVER_MSPT = builder
                .comment("离开关键感知状态所需的平滑 MSPT。")
                .defineInRange("criticalRecoverMspt", 45.0D, 1.0D, 199.0D);
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
