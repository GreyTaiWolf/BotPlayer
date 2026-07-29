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
    public static final ModConfigSpec.IntValue NAVIGATION_HORIZONTAL_RADIUS;
    public static final ModConfigSpec.IntValue NAVIGATION_VERTICAL_RADIUS;
    public static final ModConfigSpec.IntValue NAVIGATION_SNAPSHOT_CELLS_PER_BOT_TICK;
    public static final ModConfigSpec.IntValue NAVIGATION_SNAPSHOT_GLOBAL_CELLS_PER_TICK;
    public static final ModConfigSpec.IntValue NAVIGATION_MAXIMUM_SNAPSHOT_TICKS;
    public static final ModConfigSpec.IntValue NAVIGATION_MAXIMUM_EXPANSIONS;
    public static final ModConfigSpec.IntValue NAVIGATION_MAXIMUM_CONCURRENT_PLANS;
    public static final ModConfigSpec.IntValue NAVIGATION_MAXIMUM_QUEUED_PLANS;
    public static final ModConfigSpec.IntValue NAVIGATION_MAXIMUM_GOAL_DISTANCE;
    public static final ModConfigSpec.IntValue NAVIGATION_FOLLOWER_INPUT_TICKS;
    public static final ModConfigSpec.IntValue NAVIGATION_STUCK_WINDOW_TICKS;
    public static final ModConfigSpec.DoubleValue NAVIGATION_WAYPOINT_TOLERANCE;
    public static final ModConfigSpec.IntValue NAVIGATION_MINIMUM_SPRINT_FOOD;
    public static final ModConfigSpec.IntValue NAVIGATION_MINIMUM_TRAVEL_FOOD;
    public static final ModConfigSpec.DoubleValue NAVIGATION_MINIMUM_TRAVEL_HEALTH;

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

        builder.push("navigation");
        NAVIGATION_HORIZONTAL_RADIUS = builder
                .comment("单次局部导航快照的水平半径。")
                .defineInRange("horizontalRadius", 24, 4, 48);
        NAVIGATION_VERTICAL_RADIUS = builder
                .comment("单次局部导航快照的垂直半径。")
                .defineInRange("verticalRadius", 8, 2, 16);
        NAVIGATION_SNAPSHOT_CELLS_PER_BOT_TICK = builder
                .comment("单个 bot 每 Tick 最多采样的运动单元格。")
                .defineInRange("snapshotCellsPerBotTick", 2048, 64, 8192);
        NAVIGATION_SNAPSHOT_GLOBAL_CELLS_PER_TICK = builder
                .comment("所有 bot 每 Tick 共享的运动快照采样预算。")
                .defineInRange("snapshotGlobalCellsPerTick", 8192, 64, 65536);
        NAVIGATION_MAXIMUM_SNAPSHOT_TICKS = builder
                .comment("局部运动快照允许跨越的最大 Tick 数。")
                .defineInRange("maximumSnapshotTicks", 20, 1, 100);
        NAVIGATION_MAXIMUM_EXPANSIONS = builder
                .comment("单次有界 A* 允许扩展的最大节点数。")
                .defineInRange("maximumExpansions", 50000, 100, 250000);
        NAVIGATION_MAXIMUM_CONCURRENT_PLANS = builder
                .comment("规划工作池允许同时执行的任务数。")
                .defineInRange("maximumConcurrentPlans", 2, 1, 8);
        NAVIGATION_MAXIMUM_QUEUED_PLANS = builder
                .comment("规划工作池的有界等待队列容量。")
                .defineInRange("maximumQueuedPlans", 16, 1, 128);
        NAVIGATION_MAXIMUM_GOAL_DISTANCE = builder
                .comment("同维度导航目标允许的最大水平距离。")
                .defineInRange("maximumGoalDistance", 2048, 16, 16384);
        NAVIGATION_FOLLOWER_INPUT_TICKS = builder
                .comment("路线 follower 每次提交的短输入租约 Tick 数。")
                .defineInRange("followerInputTicks", 3, 2, 5);
        NAVIGATION_STUCK_WINDOW_TICKS = builder
                .comment("动作后端用于检测无进展的滚动 Tick 窗口。")
                .defineInRange("stuckWindowTicks", 20, 5, 40);
        NAVIGATION_WAYPOINT_TOLERANCE = builder
                .comment("到达路线节点的水平距离容差。")
                .defineInRange("waypointTolerance", 0.45D, 0.1D, 1.0D);
        NAVIGATION_MINIMUM_SPRINT_FOOD = builder
                .comment("允许导航发出疾跑输入的最小食物值。")
                .defineInRange("minimumSprintFood", 7, 0, 20);
        NAVIGATION_MINIMUM_TRAVEL_FOOD = builder
                .comment("允许继续普通远行的最小食物值。")
                .defineInRange("minimumTravelFood", 5, 0, 20);
        NAVIGATION_MINIMUM_TRAVEL_HEALTH = builder
                .comment("允许继续普通远行的最小生命值。")
                .defineInRange("minimumTravelHealth", 6.0D, 0.0D, 2048.0D);
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
