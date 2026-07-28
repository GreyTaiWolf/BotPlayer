package io.github.greytaiwolf.botplayer.perception;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import java.util.EnumMap;
import java.util.Map;

/**
 * 从 NeoForge 配置读取并完成跨字段校验后的 P3 运行期设置。
 */
public record PerceptionSettings(
        double visualRange,
        double entityRadius,
        int localBlockRadius,
        double hearingRange,
        int entityReadsPerBot,
        int blockReadsPerBot,
        int raycastsPerBot,
        int eventReadsPerBot,
        int inventoryReadsPerBot,
        int globalWorkPerTick,
        int authorityEventCapacity,
        int eventCapacityPerBot,
        int pendingEventCapacity,
        int factCapacityPerBot,
        int revisionScopeCapacity,
        int recentEventLimit,
        int activityWindowTicks,
        double degradeMspt,
        double criticalMspt,
        double recoverMspt,
        double criticalRecoverMspt) {

    private static final int PLAYER_INVENTORY_SLOTS = 41;

    public PerceptionSettings {
        requireFiniteRange(visualRange, 4.0D, 64.0D, "visualRange");
        requireFiniteRange(entityRadius, 4.0D, 64.0D, "entityRadius");
        requireRange(localBlockRadius, 0, 2, "localBlockRadius");
        requireFiniteRange(hearingRange, 4.0D, 128.0D, "hearingRange");
        requireRange(entityReadsPerBot, 1, 512, "entityReadsPerBot");
        requireRange(blockReadsPerBot, 1, 1_024, "blockReadsPerBot");
        requireRange(raycastsPerBot, 1, 256, "raycastsPerBot");
        requireRange(eventReadsPerBot, 1, 1_024, "eventReadsPerBot");
        requireRange(
                inventoryReadsPerBot,
                PLAYER_INVENTORY_SLOTS,
                512,
                "inventoryReadsPerBot");
        /*
         * 公开传感器池占总量的 3/4；最小 64 可保证至少 48 个工作单元，
         * 足以原子读取玩家完整 41 槽背包并发布关键快照。
         */
        requireRange(globalWorkPerTick, 64, 65_536, "globalWorkPerTick");
        requireRange(
                authorityEventCapacity,
                64,
                65_536,
                "authorityEventCapacity");
        requireRange(eventCapacityPerBot, 32, 8_192, "eventCapacityPerBot");
        requireRange(pendingEventCapacity, 32, 8_192, "pendingEventCapacity");
        requireRange(factCapacityPerBot, 64, 65_536, "factCapacityPerBot");
        requireRange(
                revisionScopeCapacity,
                64,
                65_536,
                "revisionScopeCapacity");
        requireRange(recentEventLimit, 1, 512, "recentEventLimit");
        if (recentEventLimit > eventCapacityPerBot) {
            throw new IllegalArgumentException(
                    "recentEventLimit must not exceed eventCapacityPerBot");
        }
        requireRange(activityWindowTicks, 20, 12_000, "activityWindowTicks");
        requireFiniteRange(degradeMspt, 5.0D, 200.0D, "degradeMspt");
        requireFiniteRange(criticalMspt, 5.0D, 200.0D, "criticalMspt");
        requireFiniteRange(recoverMspt, 1.0D, 199.0D, "recoverMspt");
        requireFiniteRange(
                criticalRecoverMspt,
                1.0D,
                199.0D,
                "criticalRecoverMspt");
        if (!(recoverMspt < degradeMspt
                && degradeMspt < criticalMspt
                && recoverMspt <= criticalRecoverMspt
                && criticalRecoverMspt < criticalMspt)) {
            throw new IllegalArgumentException(
                    "perception MSPT hysteresis ordering is invalid");
        }
    }

    public static PerceptionSettings fromConfig() {
        double degrade = BotPlayerConfig.PERCEPTION_DEGRADE_MSPT.get();
        double critical = BotPlayerConfig.PERCEPTION_CRITICAL_MSPT.get();
        double recover = BotPlayerConfig.PERCEPTION_RECOVER_MSPT.get();
        double criticalRecover =
                BotPlayerConfig.PERCEPTION_CRITICAL_RECOVER_MSPT.get();
        if (!(recover < degrade
                && degrade < critical
                && recover <= criticalRecover
                && criticalRecover < critical)) {
            BotPlayer.LOGGER.warn(
                    "Invalid BotPlayer perception MSPT hysteresis; using safe defaults "
                            + "recover=38, degrade=45, criticalRecover=45, critical=50");
            recover = 38.0D;
            degrade = 45.0D;
            criticalRecover = 45.0D;
            critical = 50.0D;
        }
        int eventCapacity =
                BotPlayerConfig.PERCEPTION_EVENT_CAPACITY_PER_BOT.get();
        int recentEventLimit = Math.min(
                BotPlayerConfig.PERCEPTION_RECENT_EVENT_LIMIT.get(),
                eventCapacity);
        if (recentEventLimit
                != BotPlayerConfig.PERCEPTION_RECENT_EVENT_LIMIT.get()) {
            BotPlayer.LOGGER.warn(
                    "BotPlayer recentEventLimit exceeds eventCapacityPerBot; "
                            + "using {}",
                    recentEventLimit);
        }
        int inventoryReads = Math.max(
                PLAYER_INVENTORY_SLOTS,
                BotPlayerConfig.PERCEPTION_INVENTORY_READS_PER_BOT.get());
        if (inventoryReads
                != BotPlayerConfig.PERCEPTION_INVENTORY_READS_PER_BOT.get()) {
            BotPlayer.LOGGER.warn(
                    "BotPlayer inventoryReadsPerBot 低于完整玩家背包槽位数；使用 {}",
                    inventoryReads);
        }
        return new PerceptionSettings(
                BotPlayerConfig.PERCEPTION_VISUAL_RANGE.get(),
                BotPlayerConfig.PERCEPTION_ENTITY_RADIUS.get(),
                BotPlayerConfig.PERCEPTION_LOCAL_BLOCK_RADIUS.get(),
                BotPlayerConfig.PERCEPTION_HEARING_RANGE.get(),
                BotPlayerConfig.PERCEPTION_ENTITY_READS_PER_BOT.get(),
                BotPlayerConfig.PERCEPTION_BLOCK_READS_PER_BOT.get(),
                BotPlayerConfig.PERCEPTION_RAYCASTS_PER_BOT.get(),
                BotPlayerConfig.PERCEPTION_EVENT_READS_PER_BOT.get(),
                inventoryReads,
                BotPlayerConfig.PERCEPTION_GLOBAL_WORK_PER_TICK.get(),
                BotPlayerConfig.PERCEPTION_AUTHORITY_EVENT_CAPACITY.get(),
                eventCapacity,
                BotPlayerConfig.PERCEPTION_PENDING_EVENT_CAPACITY.get(),
                BotPlayerConfig.PERCEPTION_FACT_CAPACITY_PER_BOT.get(),
                BotPlayerConfig.PERCEPTION_REVISION_SCOPE_CAPACITY.get(),
                recentEventLimit,
                BotPlayerConfig.PERCEPTION_ACTIVITY_WINDOW_TICKS.get(),
                degrade,
                critical,
                recover,
                criticalRecover);
    }

    public Map<BudgetKind, Integer> budgetLimits(PerceptionPressure pressure) {
        EnumMap<BudgetKind, Integer> limits = new EnumMap<>(BudgetKind.class);
        switch (pressure) {
            case NORMAL -> {
                limits.put(
                        BudgetKind.ENTITY_SCAN,
                        entityScanLimit(entityReadsPerBot));
                limits.put(BudgetKind.ENTITY_READ, entityReadsPerBot);
                limits.put(BudgetKind.BLOCK_READ, blockReadsPerBot);
                limits.put(BudgetKind.RAYCAST, raycastsPerBot);
                limits.put(BudgetKind.EVENT_READ, eventReadsPerBot);
                limits.put(BudgetKind.INVENTORY_SLOT, inventoryReadsPerBot);
            }
            case DEGRADED -> {
                limits.put(
                        BudgetKind.ENTITY_SCAN,
                        reduced(entityScanLimit(entityReadsPerBot), 2));
                limits.put(BudgetKind.ENTITY_READ, reduced(entityReadsPerBot, 2));
                limits.put(BudgetKind.BLOCK_READ, reduced(blockReadsPerBot, 3));
                limits.put(BudgetKind.RAYCAST, reduced(raycastsPerBot, 2));
                limits.put(BudgetKind.EVENT_READ, reduced(eventReadsPerBot, 2));
                limits.put(BudgetKind.INVENTORY_SLOT, inventoryReadsPerBot);
            }
            case CRITICAL -> {
                limits.put(
                        BudgetKind.ENTITY_SCAN,
                        reduced(entityScanLimit(entityReadsPerBot), 4));
                limits.put(BudgetKind.ENTITY_READ, reduced(entityReadsPerBot, 4));
                limits.put(BudgetKind.BLOCK_READ, 0);
                limits.put(BudgetKind.RAYCAST, reduced(raycastsPerBot, 4));
                limits.put(BudgetKind.EVENT_READ, reduced(eventReadsPerBot, 4));
                limits.put(BudgetKind.INVENTORY_SLOT, inventoryReadsPerBot);
            }
        }
        return Map.copyOf(limits);
    }

    private static int reduced(int value, int divisor) {
        return Math.max(1, value / divisor);
    }

    private static int entityScanLimit(int entityReads) {
        return Math.min(2_048, Math.multiplyExact(entityReads, 4));
    }

    private static void requireRange(
            int value, int minimum, int maximum, String field) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(
                    field
                            + " must be between "
                            + minimum
                            + " and "
                            + maximum);
        }
    }

    private static void requireFiniteRange(
            double value, double minimum, double maximum, String field) {
        if (!Double.isFinite(value)
                || value < minimum
                || value > maximum) {
            throw new IllegalArgumentException(
                    field
                            + " must be finite and between "
                            + minimum
                            + " and "
                            + maximum);
        }
    }
}
