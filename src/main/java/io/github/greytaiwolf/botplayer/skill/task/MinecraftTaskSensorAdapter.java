package io.github.greytaiwolf.botplayer.skill.task;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
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
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.AbstractFurnaceMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * TaskSensor 的唯一 Minecraft 适配器。
 *
 * <p>它只能从当前活动 Bot、已打开 menu、已加载的精确方块和 P4 已有安全帧复制有限
 * 标量；不会加载区块、读取未打开容器、写入 P3 事实或返回活对象。调用方必须通过
 * {@link TaskSensorService} 的 authority 与每 Tick 额度才能到达本类。
 */
public final class MinecraftTaskSensorAdapter implements TaskSensorSampler {
    private static final int PLAYER_INVENTORY_SLOTS = 41;

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
            case DROPPED_ITEMS, RECIPE_FEASIBILITY -> unavailable(
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
        int radius = scope.radius();
        List<TaskSensorEvidence> evidence = new ArrayList<>(maximumCandidates);
        int inspected = 0;
        boolean truncated = false;
        scan:
        for (int distance = 0; distance <= radius; distance++) {
            for (int x = -distance; x <= distance; x++) {
                for (int y = -distance; y <= distance; y++) {
                    for (int z = -distance; z <= distance; z++) {
                        if (Math.max(Math.max(Math.abs(x), Math.abs(y)),
                                Math.abs(z)) != distance) {
                            continue;
                        }
                        if (inspected++ >= maximumBlocks) {
                            truncated = true;
                            break scan;
                        }
                        BlockPos position = center.offset(x, y, z);
                        if (!player.serverLevel().isLoaded(position)) {
                            truncated = true;
                            continue;
                        }
                        BlockState state = player.serverLevel()
                                .getBlockState(position);
                        if (state.isAir()) {
                            continue;
                        }
                        evidence.add(new TaskSensorEvidence(
                                "resource.candidate",
                                new SkillParameters(Map.of(
                                        "x", position.getX(),
                                        "y", position.getY(),
                                        "z", position.getZ(),
                                        "block", BuiltInRegistries.BLOCK
                                                .getKey(state.getBlock())
                                                .toString()))));
                        if (evidence.size() >= maximumCandidates) {
                            truncated = true;
                            break scan;
                        }
                    }
                }
            }
        }
        return available(query, currentTick, truncated, evidence);
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
        if (menu instanceof AbstractFurnaceMenu && menu.slots.size() == 39) {
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
