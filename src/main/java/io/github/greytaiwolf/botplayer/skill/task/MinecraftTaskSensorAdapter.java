package io.github.greytaiwolf.botplayer.skill.task;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.menu.FurnaceKind;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.safety.SafetyFrame;
import io.github.greytaiwolf.botplayer.safety.ThreatSummary;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.BlastFurnaceMenu;
import net.minecraft.world.inventory.FurnaceMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.SmokerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;

/**
 * TaskSensor 的唯一 Minecraft 适配器。
 *
 * <p>它只能从当前活动 Bot、已打开 menu、已加载的精确方块和 P4 已有安全帧复制有限
 * 标量；不会加载区块、读取未打开容器、写入 P3 事实或返回活对象。调用方必须通过
 * {@link TaskSensorService} 的 authority 与每 Tick 额度才能到达本类。
 */
public final class MinecraftTaskSensorAdapter implements TaskSensorSampler {
    private static final int PLAYER_INVENTORY_SLOTS = 41;
    /** 掉落物只能在当前任务已定位的近场读取，不能退化为整区块实体枚举。 */
    private static final int MAX_DROPPED_ITEM_SCOPE_RADIUS = 8;

    private final BiFunction<UUID, Long, Optional<BotServerPlayer>> resolver;
    private final Function<UUID, Optional<SafetyFrame>> safetyFrames;

    public MinecraftTaskSensorAdapter(
            BiFunction<UUID, Long, Optional<BotServerPlayer>> resolver,
            Function<UUID, Optional<SafetyFrame>> safetyFrames) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.safetyFrames = Objects.requireNonNull(
                safetyFrames, "safetyFrames");
    }

    @Override
    public TaskSensorSnapshot sample(
            TaskSensorQuery query, long currentTick) {
        Objects.requireNonNull(query, "query");
        BotServerPlayer player = resolver.apply(
                query.identity().botId(), query.identity().generation())
                .orElse(null);
        if (player == null || !onServerThread(player)
                || !scopeContainsPlayer(query.scope(), player)) {
            return unavailable(query, currentTick);
        }
        return switch (query.type()) {
            case SELF_INVENTORY -> inventory(query, currentTick, player);
            case OPEN_MENU, WORKSTATION_STATE -> menu(
                    query, currentTick, player);
            case THREATS -> threats(query, currentTick, player);
            case TARGET_BLOCK -> targetBlock(query, currentTick, player);
            case RESOURCE_CANDIDATES -> resources(
                    query, currentTick, player);
            case DROPPED_ITEMS -> droppedItems(
                    query, currentTick, player);
            case RECIPE_FEASIBILITY -> unavailable(
                    query, currentTick);
        };
    }

    private static TaskSensorSnapshot inventory(
            TaskSensorQuery query, long currentTick, BotServerPlayer player) {
        if (query.budget().maximumEvidence() == 0) {
            return available(query, currentTick, true, List.of());
        }
        int limit = Math.min(
                Math.min(PLAYER_INVENTORY_SLOTS,
                        query.budget().maximumSlots()),
                query.budget().maximumEvidence() - 1);
        List<TaskSensorEvidence> evidence = new ArrayList<>(limit + 2);
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            evidence.add(inventoryEvidence(
                    slot, MinecraftActionSnapshot.item(player, stack)));
        }
        evidence.add(new TaskSensorEvidence("inventory.selected",
                new SkillParameters(Map.of(
                        "slot", player.getInventory().selected))));
        return available(query, currentTick,
                limit < PLAYER_INVENTORY_SLOTS, evidence);
    }

    private static TaskSensorSnapshot menu(
            TaskSensorQuery query, long currentTick, BotServerPlayer player) {
        AbstractContainerMenu menu = player.containerMenu;
        String family = menuFamily(player, menu);
        if (family == null || !menu.stillValid(player)) {
            return unavailable(query, currentTick);
        }
        int evidenceLimit = query.budget().maximumEvidence();
        if (evidenceLimit == 0) {
            return available(query, currentTick, true, List.of());
        }
        int fixedEvidence = evidenceLimit == 1 ? 1 : 2;
        int limit = Math.min(Math.min(menu.slots.size(),
                query.budget().maximumSlots()),
                evidenceLimit - fixedEvidence);
        List<TaskSensorEvidence> evidence = new ArrayList<>(limit + 3);
        evidence.add(new TaskSensorEvidence("menu.header",
                new SkillParameters(Map.of(
                        "family", family,
                        "container.id", menu.containerId,
                        "state.id", menu.getStateId(),
                        "slot.count", menu.slots.size()))));
        for (int slot = 0; slot < limit; slot++) {
            evidence.add(menuEvidence(slot, MinecraftActionSnapshot.item(
                    player, menu.getSlot(slot).getItem())));
        }
        if (evidenceLimit > 1) {
            evidence.add(new TaskSensorEvidence("menu.carried",
                    itemParameters(MinecraftActionSnapshot.item(
                            player, menu.getCarried()))));
        }
        return available(query, currentTick,
                limit < menu.slots.size() || evidenceLimit < 2, evidence);
    }

    private TaskSensorSnapshot threats(
            TaskSensorQuery query, long currentTick, BotServerPlayer player) {
        SafetyFrame frame = safetyFrames.apply(player.getUUID())
                .filter(value -> value.botGeneration()
                        == query.identity().generation()
                        && value.gameTick() == currentTick)
                .orElse(null);
        if (frame == null || frame.threatCoverageIncomplete()) {
            return unavailable(query, currentTick);
        }
        int limit = Math.min(Math.min(
                frame.threats().size(), query.budget().maximumCandidates()),
                query.budget().maximumEvidence());
        List<ThreatSummary> ordered = frame.threats().stream()
                .sorted(Comparator.comparingDouble(ThreatSummary::distance)
                        .thenComparing(value -> value.entityId().toString()))
                .limit(limit)
                .toList();
        List<TaskSensorEvidence> evidence = new ArrayList<>(ordered.size());
        for (ThreatSummary threat : ordered) {
            evidence.add(new TaskSensorEvidence("threat.candidate",
                    new SkillParameters(Map.of(
                            "entity.id", threat.entityId().toString(),
                            "kind", threat.kind().name(),
                            "distance", threat.distance(),
                            "targeting", threat.targetingBot()))));
        }
        return available(query, currentTick,
                limit < frame.threats().size(), evidence);
    }

    private static TaskSensorSnapshot targetBlock(
            TaskSensorQuery query, long currentTick, BotServerPlayer player) {
        if (query.budget().maximumEvidence() == 0) {
            return available(query, currentTick, true, List.of());
        }
        BlockPos position = center(query.scope());
        if (!player.serverLevel().isLoaded(position)) {
            return unavailable(query, currentTick);
        }
        BlockState state = player.serverLevel().getBlockState(position);
        String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock())
                .toString();
        return available(query, currentTick, false,
                List.of(new TaskSensorEvidence("target.block",
                        new SkillParameters(Map.of(
                                "x", position.getX(),
                                "y", position.getY(),
                                "z", position.getZ(),
                                "block", blockId,
                                "air", state.isAir())))));
    }

    private static TaskSensorSnapshot resources(
            TaskSensorQuery query, long currentTick, BotServerPlayer player) {
        int maximumBlocks = query.budget().maximumBlocks();
        int maximumCandidates = Math.min(
                query.budget().maximumCandidates(),
                query.budget().maximumEvidence());
        if (maximumBlocks == 0 || maximumCandidates == 0) {
            return available(query, currentTick, true, List.of());
        }
        TaskSensorScope scope = query.scope();
        BlockPos center = center(scope);
        BlockPos below = center.below();
        boolean inspectedBelow = false;
        boolean prioritizeLayerBelow = false;
        String belowBlockId = null;
        if (supportsLayerBelowPriority(query.resourceFilter())
                && maximumBlocks > 1
                && scope.radius() >= 3
                && player.serverLevel().isLoaded(below)) {
            BlockState belowState = player.serverLevel().getBlockState(below);
            belowBlockId = BuiltInRegistries.BLOCK.getKey(
                    belowState.getBlock()).toString();
            inspectedBelow = true;
            prioritizeLayerBelow = shouldPrioritizeLayerBelow(
                    query.resourceFilter(), belowBlockId);
        }
        List<TaskSensorEvidence> evidence = new ArrayList<>(maximumCandidates);
        ResourceScanPlan plan = prioritizeLayerBelow
                ? resourceScanPlanPrioritizingLayerBelow(
                        scope, maximumBlocks, query.resourceFilter())
                : resourceScanPlan(scope, maximumBlocks,
                        query.resourceFilter());
        boolean truncated = plan.truncatedByBudget();
        int remainingReads = maximumBlocks - (inspectedBelow ? 1 : 0);
        if (prioritizeLayerBelow && query.resourceFilter().accepts(
                Objects.requireNonNull(belowBlockId, "belowBlockId"))) {
            evidence.add(resourceEvidence(below, belowBlockId));
        }
        for (BlockPos position : plan.positions()) {
            if (inspectedBelow && position.equals(below)) {
                continue;
            }
            if (remainingReads <= 0) {
                truncated = true;
                break;
            }
            remainingReads--;
            if (evidence.size() >= maximumCandidates) {
                truncated = true;
                break;
            }
            if (!player.serverLevel().isLoaded(position)) {
                truncated = true;
                continue;
            }
            BlockState state = player.serverLevel().getBlockState(position);
            if (state.isAir()) {
                continue;
            }
            String blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock())
                    .toString();
            if (!query.resourceFilter().accepts(blockId)) {
                continue;
            }
            evidence.add(resourceEvidence(position, blockId));
            if (evidence.size() >= maximumCandidates) {
                truncated = true;
                break;
            }
        }
        return available(query, currentTick, truncated, evidence);
    }

    private static TaskSensorEvidence resourceEvidence(
            BlockPos position, String blockId) {
        Objects.requireNonNull(position, "position");
        Objects.requireNonNull(blockId, "blockId");
        return new TaskSensorEvidence("resource.candidate",
                new SkillParameters(Map.of(
                        "x", position.getX(),
                        "y", position.getY(),
                        "z", position.getZ(),
                        "block", blockId)));
    }

    /**
     * Builds the finite read order for a resource query without reading or loading a block.
     *
     * <p>Legacy unfiltered callers keep the historical 3-D Chebyshev-shell order. A reviewed
     * exact resource filter instead keeps the complete nearby 3-D cube through distance two and
     * spends its remaining read budget on the current body layer. The latter matters for normal
     * mining: a 256-read budget cannot cover an {@code r=8} 3-D cube, and spending its entire
     * prefix on air above and below the player can hide a same-layer resource that is still in
     * the declared local scope. This only changes observation priority; it neither increases the
     * cap nor makes a truncated observation authoritative.
     */
    static ResourceScanPlan resourceScanPlan(
            TaskSensorScope scope,
            int maximumBlocks,
            TaskSensorResourceFilter resourceFilter) {
        return resourceScanPlan(scope, maximumBlocks, resourceFilter, false);
    }

    /**
     * 返回同一份有界精确过滤计划，但把采矿平面放在当前 body 下一格。P5 资源导航允许
     * 已接地 body 停在某种经审计资源的顶面；此时所有仍待采集的 P5A 资源共用下方地形层，
     * 即使承托 body 的资源并非当前查询的资源。超过近场三维立方体后，必须优先该层而不是
     * body 所在层。
     */
    static ResourceScanPlan resourceScanPlanPrioritizingLayerBelow(
            TaskSensorScope scope,
            int maximumBlocks,
            TaskSensorResourceFilter resourceFilter) {
        if (Objects.requireNonNull(scope, "scope").radius() < 3) {
            throw new IllegalArgumentException(
                    "a below-layer resource plan requires scope radius at least three");
        }
        if (!supportsLayerBelowPriority(Objects.requireNonNull(
                resourceFilter, "resourceFilter"))) {
            throw new IllegalArgumentException(
                    "only audited P5 filters can prioritize a lower layer");
        }
        return resourceScanPlan(scope, maximumBlocks, resourceFilter, true);
    }

    static boolean shouldPrioritizeLayerBelow(
            TaskSensorResourceFilter resourceFilter, String belowBlockId) {
        Objects.requireNonNull(resourceFilter, "resourceFilter");
        Objects.requireNonNull(belowBlockId, "belowBlockId");
        return supportsLayerBelowPriority(resourceFilter)
                && isReviewedP5ResourceBlock(belowBlockId);
    }

    /**
     * Only audited P5A resource and workstation filters can use the top-of-resource arrival
     * state. A resource navigation fragment may legally hand off while the body is standing on
     * the mined resource. The immediately following recipe must still be able to rediscover its
     * already-placed crafting table or furnace on that shared lower layer; this changes only the
     * finite read priority, never the exact filter, scope, or evidence contract.
     */
    private static boolean supportsLayerBelowPriority(
            TaskSensorResourceFilter resourceFilter) {
        Objects.requireNonNull(resourceFilter, "resourceFilter");
        return switch (resourceFilter) {
            case OAK_LOG,
                    COBBLESTONE,
                    IRON_ORE,
                    COAL_ORE,
                    CRAFTING_TABLE,
                    FURNACE,
                    BLAST_FURNACE,
                    SMOKER -> true;
            case UNFILTERED -> false;
        };
    }

    /**
     * 只有 body 正站在 P5A 精确采集资源之一时才允许采用下层排序；任意地面、工作站或未来
     * 未在此显式审计的过滤器都不能开启该优先级。
     */
    private static boolean isReviewedP5ResourceBlock(String blockId) {
        return switch (Objects.requireNonNull(blockId, "blockId")) {
            case "minecraft:oak_log",
                    "minecraft:cobblestone",
                    "minecraft:iron_ore",
                    "minecraft:coal_ore" -> true;
            default -> false;
        };
    }

    private static ResourceScanPlan resourceScanPlan(
            TaskSensorScope scope,
            int maximumBlocks,
            TaskSensorResourceFilter resourceFilter,
            boolean prioritizeLayerBelow) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(resourceFilter, "resourceFilter");
        if (maximumBlocks < 0) {
            throw new IllegalArgumentException(
                    "maximumBlocks must be non-negative");
        }
        int capacity = Math.min(maximumBlocks,
                Math.toIntExact(Math.min(totalScopePositions(scope.radius()),
                        (long) Integer.MAX_VALUE)));
        List<BlockPos> positions = new ArrayList<>(capacity);
        if (maximumBlocks == 0) {
            return new ResourceScanPlan(List.of(),
                    totalScopePositions(scope.radius()) > 0L);
        }
        BlockPos center = center(scope);
        if (resourceFilter.isUnfiltered()
                || maximumBlocks >= totalScopePositions(scope.radius())) {
            appendCubeShells(positions, center, 0, scope.radius(),
                    maximumBlocks);
        } else {
            /*
             * Preserve the old complete near-body 3-D coverage first. With r=8 and the reviewed
             * 256-read cap this consumes 125 positions, leaving the rest for useful same-height
             * mining candidates rather than the arbitrary prefix of the d=3 cube shell.
             */
            int nearRadius = Math.min(2, scope.radius());
            appendCubeShells(positions, center, 0, nearRadius,
                    maximumBlocks);
            appendLayerRings(positions,
                    prioritizeLayerBelow ? center.below() : center,
                    nearRadius + 1, scope.radius(), maximumBlocks);
        }
        return new ResourceScanPlan(List.copyOf(positions),
                positions.size() < totalScopePositions(scope.radius()));
    }

    private static void appendCubeShells(
            List<BlockPos> positions,
            BlockPos center,
            int minimumDistance,
            int maximumDistance,
            int maximumBlocks) {
        for (int distance = minimumDistance;
                distance <= maximumDistance
                        && positions.size() < maximumBlocks;
                distance++) {
            for (int x = -distance;
                    x <= distance && positions.size() < maximumBlocks;
                    x++) {
                for (int y = -distance;
                        y <= distance && positions.size() < maximumBlocks;
                        y++) {
                    for (int z = -distance;
                            z <= distance && positions.size() < maximumBlocks;
                            z++) {
                        if (Math.max(Math.max(Math.abs(x), Math.abs(y)),
                                Math.abs(z)) != distance) {
                            continue;
                        }
                        positions.add(center.offset(x, y, z));
                    }
                }
            }
        }
    }

    private static void appendLayerRings(
            List<BlockPos> positions,
            BlockPos center,
            int minimumDistance,
            int maximumDistance,
            int maximumBlocks) {
        for (int distance = minimumDistance;
                distance <= maximumDistance
                        && positions.size() < maximumBlocks;
                distance++) {
            for (int x = -distance;
                    x <= distance && positions.size() < maximumBlocks;
                    x++) {
                for (int z = -distance;
                        z <= distance && positions.size() < maximumBlocks;
                        z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) != distance) {
                        continue;
                    }
                    positions.add(center.offset(x, 0, z));
                }
            }
        }
    }

    private static long totalScopePositions(int radius) {
        long side = Math.addExact(Math.multiplyExact((long) radius, 2L),
                1L);
        return Math.multiplyExact(Math.multiplyExact(side, side), side);
    }

    record ResourceScanPlan(List<BlockPos> positions,
                            boolean truncatedByBudget) {
        ResourceScanPlan {
            positions = List.copyOf(Objects.requireNonNull(positions,
                    "positions"));
        }
    }

    /**
     * 只复制近场已加载 ItemEntity 的标量候选。
     *
     * <p>实体索引直接以 {@code maximumCandidates + 1} 截断，额外的一项只用于把不完整
     * 结果标记为 truncated；因此不会先构造无界的实体候选列表。结果按相对 scope 中心的
     * 距离和 UUID 稳定排序。由于已加载边界和实体预算都会使结果不完整，任一边界都显式
     * 返回 truncated，调用方不得将空集解释为“附近没有掉落物”。
     */
    private static TaskSensorSnapshot droppedItems(
            TaskSensorQuery query, long currentTick, BotServerPlayer player) {
        TaskSensorScope scope = query.scope();
        int maximumCandidates = query.budget().maximumCandidates();
        int maximumEvidence = query.budget().maximumEvidence();
        if (scope.radius() > MAX_DROPPED_ITEM_SCOPE_RADIUS) {
            return unavailable(query, currentTick);
        }
        if (maximumCandidates == 0 || maximumEvidence == 0) {
            return available(query, currentTick, true, List.of());
        }

        List<ItemEntity> candidates = new ArrayList<>(
                Math.incrementExact(maximumCandidates));
        player.serverLevel().getEntities(
                EntityTypeTest.<Entity, ItemEntity>forClass(ItemEntity.class),
                droppedItemBounds(scope),
                item -> readableDroppedItem(player, scope, item),
                candidates,
                Math.incrementExact(maximumCandidates));
        candidates.sort(Comparator.<ItemEntity>comparingDouble(
                        item -> squaredDistanceToScopeCenter(item, scope))
                .thenComparing(item -> item.getUUID().toString()));

        int limit = Math.min(maximumCandidates, maximumEvidence);
        boolean truncated = scopeHasUnloadedChunks(player, scope)
                || candidates.size() > limit;
        List<TaskSensorEvidence> evidence = new ArrayList<>(
                Math.min(candidates.size(), limit));
        for (int index = 0; index < candidates.size() && index < limit;
                index++) {
            evidence.add(droppedItemEvidence(candidates.get(index)));
        }
        return available(query, currentTick, truncated, evidence);
    }

    private static AABB droppedItemBounds(TaskSensorScope scope) {
        int radius = scope.radius();
        return new AABB(
                scope.centerX() - radius,
                scope.centerY() - radius,
                scope.centerZ() - radius,
                scope.centerX() + radius + 1.0D,
                scope.centerY() + radius + 1.0D,
                scope.centerZ() + radius + 1.0D);
    }

    private static boolean readableDroppedItem(
            BotServerPlayer player,
            TaskSensorScope scope,
            ItemEntity item) {
        if (item.isRemoved() || item.getItem().isEmpty()) {
            return false;
        }
        BlockPos position = item.blockPosition();
        return player.serverLevel().isLoaded(position)
                && withinScope(scope, position);
    }

    private static boolean withinScope(
            TaskSensorScope scope, BlockPos position) {
        int radius = scope.radius();
        return Math.abs(position.getX() - scope.centerX()) <= radius
                && Math.abs(position.getY() - scope.centerY()) <= radius
                && Math.abs(position.getZ() - scope.centerZ()) <= radius;
    }

    private static boolean scopeHasUnloadedChunks(
            BotServerPlayer player, TaskSensorScope scope) {
        int radius = scope.radius();
        int minimumChunkX = Math.floorDiv(scope.centerX() - radius, 16);
        int maximumChunkX = Math.floorDiv(scope.centerX() + radius, 16);
        int minimumChunkZ = Math.floorDiv(scope.centerZ() - radius, 16);
        int maximumChunkZ = Math.floorDiv(scope.centerZ() + radius, 16);
        for (int chunkX = minimumChunkX;
                chunkX <= maximumChunkX;
                chunkX++) {
            for (int chunkZ = minimumChunkZ;
                    chunkZ <= maximumChunkZ;
                    chunkZ++) {
                if (!player.serverLevel().isLoaded(new BlockPos(
                        chunkX << 4,
                        scope.centerY(),
                        chunkZ << 4))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static double squaredDistanceToScopeCenter(
            ItemEntity item, TaskSensorScope scope) {
        double deltaX = item.getX() - (scope.centerX() + 0.5D);
        double deltaY = item.getY() - (scope.centerY() + 0.5D);
        double deltaZ = item.getZ() - (scope.centerZ() + 0.5D);
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }

    private static TaskSensorEvidence droppedItemEvidence(ItemEntity item) {
        ItemStack stack = item.getItem();
        BlockPos position = item.blockPosition();
        String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem())
                .toString();
        return new TaskSensorEvidence("dropped_item.candidate",
                new SkillParameters(Map.of(
                        "entity.id", item.getUUID().toString(),
                        "item", itemId,
                        "count", stack.getCount(),
                        "x", position.getX(),
                        "y", position.getY(),
                        "z", position.getZ())));
    }

    private static TaskSensorEvidence inventoryEvidence(
            int slot, ItemStackFingerprint item) {
        Map<String, Object> values = new java.util.LinkedHashMap<>(
                itemParameters(item).values());
        values.put("slot", slot);
        return new TaskSensorEvidence("inventory.slot",
                new SkillParameters(values));
    }

    private static TaskSensorEvidence menuEvidence(
            int slot, ItemStackFingerprint item) {
        Map<String, Object> values = new java.util.LinkedHashMap<>(
                itemParameters(item).values());
        values.put("slot", slot);
        return new TaskSensorEvidence("menu.slot",
                new SkillParameters(values));
    }

    private static SkillParameters itemParameters(
            ItemStackFingerprint item) {
        Map<String, Object> values = new java.util.LinkedHashMap<>();
        values.put("empty", item.isEmpty());
        values.put("count", item.count());
        if (!item.isEmpty()) {
            values.put("item", item.itemId().orElseThrow().value());
            values.put("damage", item.damage());
            values.put("digest", item.componentsDigest().orElseThrow());
        }
        return new SkillParameters(values);
    }

    private static String menuFamily(
            BotServerPlayer player, AbstractContainerMenu menu) {
        if (menu == player.inventoryMenu && menu instanceof InventoryMenu
                && menu.slots.size() == 46) {
            return "inventory_2x2";
        }
        if (menu instanceof CraftingMenu && menu.slots.size() == 46) {
            return "crafting_3x3";
        }
        if (exactVanillaFurnaceKind(menu.getClass()).isPresent()
                && menu.slots.size() == 39) {
            return "furnace";
        }
        if (menu instanceof ChestMenu chest
                && chest.getRowCount() == 3
                && chest.getContainer().getContainerSize() == 27
                && menu.slots.size() == 63) {
            return "chest_3x9";
        }
        return null;
    }

    /**
     * OPEN_MENU 也不能把模组 {@code AbstractFurnaceMenu} 子类作为原版工作站证据。三种已审核
     * 炉型仍共享布局 family；真实 recipe action 另以 frozen FurnaceKind 严格绑定方块和菜单。
     */
    static Optional<FurnaceKind> exactVanillaFurnaceKind(
            Class<?> menuClass) {
        Objects.requireNonNull(menuClass, "menuClass");
        if (menuClass == FurnaceMenu.class) {
            return Optional.of(FurnaceKind.FURNACE);
        }
        if (menuClass == BlastFurnaceMenu.class) {
            return Optional.of(FurnaceKind.BLAST_FURNACE);
        }
        if (menuClass == SmokerMenu.class) {
            return Optional.of(FurnaceKind.SMOKER);
        }
        return Optional.empty();
    }

    private static boolean onServerThread(BotServerPlayer player) {
        MinecraftServer server = player.getServer();
        return server != null && server.isSameThread();
    }

    private static boolean scopeContainsPlayer(
            TaskSensorScope scope, BotServerPlayer player) {
        if (!scope.dimensionId().equals(player.serverLevel()
                .dimension().location().toString())) {
            return false;
        }
        double dx = player.getX() - (scope.centerX() + 0.5D);
        double dy = player.getY() - (scope.centerY() + 0.5D);
        double dz = player.getZ() - (scope.centerZ() + 0.5D);
        double radius = scope.radius();
        return dx * dx + dy * dy + dz * dz
                <= radius * radius + 1.0D;
    }

    private static BlockPos center(TaskSensorScope scope) {
        return new BlockPos(scope.centerX(), scope.centerY(),
                scope.centerZ());
    }

    private static TaskSensorSnapshot available(
            TaskSensorQuery query,
            long currentTick,
            boolean truncated,
            List<TaskSensorEvidence> evidence) {
        return new TaskSensorSnapshot(query, currentTick,
                TaskSensorAvailability.AVAILABLE, truncated, evidence);
    }

    private static TaskSensorSnapshot unavailable(
            TaskSensorQuery query, long currentTick) {
        return new TaskSensorSnapshot(query, currentTick,
                TaskSensorAvailability.UNAVAILABLE, false, List.of());
    }
}
