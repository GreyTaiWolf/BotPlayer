package io.github.greytaiwolf.botplayer.skill.checkpoint;

import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionSkillPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanEdge;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeCheckpoint;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;

/**
 * P5A 内建生产计划的服务器线程检查点范围观察器。
 *
 * <p>这不是通用的“背包摘要”：只有已编译进二进制的 bootstrap_iron 全计划及其受控恢复后缀
 * 才能取得范围。每次调用都重新读取原版背包、落脚点和附近工作站；不会保留玩家、世界或菜单
 * 对象。当前范围只适用于所有原版菜单已关闭、没有跨 checkpoint 的容器状态的原子生产步骤；
 * chest transfer、未完成熔炼或其它方块实体状态在被纳入专门范围前不得复用本格式。任何无法
 * 完整观察的情况都返回空/不完整结果，让生命周期保守拒绝恢复。
 */
public final class MinecraftSkillCheckpointScopeObserver {
    static final String FORMAT = "p5a-scope-v1";
    private static final int WORKSTATION_RADIUS = 8;
    private static final int MAX_WORKSTATIONS = 256;
    private static final String CRAFTING_TABLE = "minecraft:crafting_table";
    private static final String FURNACE = "minecraft:furnace";

    /**
     * 在一个已经由 lifecycle 证明静止的安全点捕获新的范围。
     */
    public Optional<SkillCheckpointScope> capture(
            BotServerPlayer player,
            SkillRuntimeCheckpoint runtime,
            SkillCheckpointPlan sourcePlan,
            Optional<SkillCheckpointRestartPlan> restartPlan,
            long observedTick) {
        try {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(runtime, "runtime");
            Objects.requireNonNull(sourcePlan, "sourcePlan");
            Objects.requireNonNull(restartPlan, "restartPlan");
            requireTick(observedTick);
            if (!supportsCanonicalRuntime(
                    player, runtime, sourcePlan, restartPlan)) {
                return Optional.empty();
            }
            Observation observation = observe(player).orElse(null);
            if (observation == null) {
                return Optional.empty();
            }
            return Optional.of(new SkillCheckpointScope(
                    observation.dimensionId(),
                    observation.anchor().getX(),
                    observation.anchor().getY(),
                    observation.anchor().getZ(),
                    Optional.empty(),
                    observation.fingerprint(),
                    FORMAT));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * 重新读取新 body 的有限范围。成功观察与旧摘要不一致时仍返回完整观察，使纯判定器能够
     * 精确给出 fingerprint mismatch；任何未加载、菜单或计划问题一律返回不完整。
     */
    public SkillCheckpointReobservation reobserve(
            BotServerPlayer player,
            SkillCheckpoint checkpoint,
            SkillPlan approvedFullPlan,
            long observedTick) {
        try {
            Objects.requireNonNull(player, "player");
            Objects.requireNonNull(checkpoint, "checkpoint");
            Objects.requireNonNull(approvedFullPlan, "approvedFullPlan");
            requireTick(observedTick);
            SkillCheckpointScope scope = checkpoint.scope().orElse(null);
            if (scope == null
                    || !FORMAT.equals(scope.verifiedSummary())
                    || scope.targetEntityId().isPresent()
                    || !checkpoint.playerId().equals(player.getUUID())
                    || !checkpoint.botId().equals(player.runtimeHandle().botId())
                    || !supportsCanonicalApprovedPlan(
                            checkpoint, approvedFullPlan)) {
                return incomplete();
            }
            Observation observation = observe(player).orElse(null);
            if (observation == null) {
                return incomplete();
            }
            if (!scope.dimensionId().equals(observation.dimensionId())
                    || scope.blockX() != observation.anchor().getX()
                    || scope.blockY() != observation.anchor().getY()
                    || scope.blockZ() != observation.anchor().getZ()) {
                return incomplete();
            }
            return new SkillCheckpointReobservation(
                    true,
                    Optional.of(observation.fingerprint()),
                    List.of(new CheckpointEvidence(
                            "p5a.scope.reobserved",
                            "P5A 检查点范围已由当前原版状态重新观察",
                            observedTick)));
        } catch (RuntimeException exception) {
            return incomplete();
        }
    }

    private static SkillCheckpointReobservation incomplete() {
        return new SkillCheckpointReobservation(
                false, Optional.empty(), List.of());
    }

    private static boolean supportsCanonicalRuntime(
            BotServerPlayer player,
            SkillRuntimeCheckpoint runtime,
            SkillCheckpointPlan sourcePlan,
            Optional<SkillCheckpointRestartPlan> restartPlan) {
        if (!runtime.view().botId().equals(player.runtimeHandle().botId())
                || runtime.view().botGeneration()
                        != player.runtimeHandle().generation()
                || !runtime.plan().botId().equals(runtime.view().botId())
                || runtime.plan().revision() != sourcePlan.revision()) {
            return false;
        }
        SkillPlan canonical = canonicalPlan(
                runtime.view().botId(), sourcePlan.revision());
        if (!referenceOf(canonical).equals(sourcePlan)) {
            return false;
        }
        if (restartPlan.isEmpty()) {
            return runtime.plan().equals(canonical);
        }
        SkillCheckpointRestartPlan restart = restartPlan.orElseThrow();
        return restart.sourcePlan().equals(sourcePlan)
                && restart.suffixPlan().equals(runtime.plan())
                && isCanonicalPlanSubset(restart.suffixPlan(), canonical);
    }

    private static boolean supportsCanonicalApprovedPlan(
            SkillCheckpoint checkpoint, SkillPlan approvedFullPlan) {
        if (!approvedFullPlan.botId().equals(checkpoint.botId())
                || approvedFullPlan.revision()
                        != checkpoint.plan().revision()
                || !referenceOf(approvedFullPlan).equals(checkpoint.plan())) {
            return false;
        }
        SkillPlan canonical = canonicalPlan(
                checkpoint.botId(), checkpoint.plan().revision());
        return canonical.equals(approvedFullPlan);
    }

    private static SkillPlan canonicalPlan(UUID botId, long revision) {
        return ProductionSkillPlanCompiler.p5aDefault()
                .compileWoodToIronPick(botId, revision);
    }

    private static SkillCheckpointPlan referenceOf(SkillPlan plan) {
        return new SkillCheckpointPlan(
                plan.planId(),
                plan.revision(),
                SkillCheckpointBridge.planDigest(plan));
    }

    /**
     * 恢复运行的 suffix 在此被检查为完整 canonical plan 的精确节点和边子集；其具体
     * planId、完成前缀及节点集合还必须与 lifecycle 已保存的 {@link SkillCheckpointRestartPlan}
     * 全等，不能借相同 revision 塞入任意 descriptor。
     */
    private static boolean isCanonicalPlanSubset(
            SkillPlan candidate, SkillPlan canonical) {
        if (!candidate.botId().equals(canonical.botId())
                || candidate.revision() != canonical.revision()
                || candidate.nodes().isEmpty()
                || candidate.nodes().size() > canonical.nodes().size()
                || candidate.edges().size() > canonical.edges().size()) {
            return false;
        }
        Map<UUID, SkillPlanNode> canonicalNodes = new HashMap<>();
        for (SkillPlanNode node : canonical.nodes()) {
            if (canonicalNodes.put(node.nodeId(), node) != null) {
                return false;
            }
        }
        Set<UUID> candidateNodeIds = new HashSet<>();
        for (SkillPlanNode node : candidate.nodes()) {
            if (!candidateNodeIds.add(node.nodeId())
                    || !node.equals(canonicalNodes.get(node.nodeId()))) {
                return false;
            }
        }
        Set<SkillPlanEdge> canonicalEdges = new HashSet<>(canonical.edges());
        Set<SkillPlanEdge> candidateEdges = new HashSet<>();
        for (SkillPlanEdge edge : candidate.edges()) {
            if (!candidateNodeIds.contains(edge.prerequisiteNodeId())
                    || !candidateNodeIds.contains(edge.dependentNodeId())
                    || !candidateEdges.add(edge)
                    || !canonicalEdges.contains(edge)) {
                return false;
            }
        }
        return true;
    }

    private static Optional<Observation> observe(BotServerPlayer player) {
        if (player.containerMenu != player.inventoryMenu
                || !player.inventoryMenu.stillValid(player)
                || player.inventoryMenu.slots.size()
                        != PlayerInventoryMenuLayout.LAST_MENU_SLOT + 1
                || !player.inventoryMenu.getCarried().isEmpty()) {
            return Optional.empty();
        }
        InventoryMenuSnapshot inventory = MinecraftActionSnapshot.inventoryMenu(
                player);
        if (!inventory.cursor().isEmpty()) {
            return Optional.empty();
        }
        BlockPos anchor = BlockPos.containing(
                player.getX(), player.getY(), player.getZ());
        if (!withinScopeBounds(anchor)) {
            return Optional.empty();
        }
        BlockTargetFingerprint support = snapshotLoaded(
                player, anchor.below()).orElse(null);
        BlockTargetFingerprint body = snapshotLoaded(player, anchor).orElse(null);
        BlockTargetFingerprint head = snapshotLoaded(player, anchor.above()).orElse(null);
        if (support == null || body == null || head == null) {
            return Optional.empty();
        }
        List<BlockTargetFingerprint> workstations = nearbyWorkstations(
                player, anchor).orElse(null);
        if (workstations == null) {
            return Optional.empty();
        }
        String dimensionId = player.serverLevel().dimension().location().toString();
        String fingerprint = fingerprint(
                dimensionId,
                anchor,
                inventory,
                support,
                body,
                head,
                workstations);
        return Optional.of(new Observation(
                dimensionId,
                anchor,
                fingerprint));
    }

    private static Optional<BlockTargetFingerprint> snapshotLoaded(
            BotServerPlayer player, BlockPos position) {
        if (!player.serverLevel().isLoaded(position)) {
            return Optional.empty();
        }
        return Optional.of(MinecraftActionSnapshot.block(player, position));
    }

    /**
     * 完整扫描半径内的已加载方块，避免把“没被抽样到”错误当作“不存在工作站”。扫描仅发生在
     * 状态机的静止 checkpoint 上；若任一点未加载或工作站数量异常大，宁可拒绝该 checkpoint。
     */
    private static Optional<List<BlockTargetFingerprint>> nearbyWorkstations(
            BotServerPlayer player, BlockPos anchor) {
        List<BlockTargetFingerprint> result = new ArrayList<>();
        for (int x = -WORKSTATION_RADIUS; x <= WORKSTATION_RADIUS; x++) {
            for (int y = -WORKSTATION_RADIUS; y <= WORKSTATION_RADIUS; y++) {
                for (int z = -WORKSTATION_RADIUS;
                        z <= WORKSTATION_RADIUS; z++) {
                    BlockPos position = anchor.offset(x, y, z);
                    if (!player.serverLevel().isLoaded(position)) {
                        return Optional.empty();
                    }
                    String blockId = BuiltInRegistries.BLOCK.getKey(
                            player.serverLevel().getBlockState(position)
                                    .getBlock()).toString();
                    if (!CRAFTING_TABLE.equals(blockId)
                            && !FURNACE.equals(blockId)) {
                        continue;
                    }
                    BlockTargetFingerprint snapshot = MinecraftActionSnapshot.block(
                            player, position);
                    if (result.size() >= MAX_WORKSTATIONS) {
                        return Optional.empty();
                    }
                    result.add(snapshot);
                }
            }
        }
        result.sort(Comparator.comparing((BlockTargetFingerprint value) ->
                value.state().blockId().value()).thenComparingInt(value ->
                        value.position().x()).thenComparingInt(value ->
                                value.position().y()).thenComparingInt(value ->
                                        value.position().z()));
        return Optional.of(List.copyOf(result));
    }

    private static boolean withinScopeBounds(BlockPos anchor) {
        return anchor.getX() >= -SkillCheckpointScope.MAX_ABSOLUTE_COORDINATE
                + WORKSTATION_RADIUS
                && anchor.getX() <= SkillCheckpointScope.MAX_ABSOLUTE_COORDINATE
                        - WORKSTATION_RADIUS
                && anchor.getZ() >= -SkillCheckpointScope.MAX_ABSOLUTE_COORDINATE
                        + WORKSTATION_RADIUS
                && anchor.getZ() <= SkillCheckpointScope.MAX_ABSOLUTE_COORDINATE
                        - WORKSTATION_RADIUS;
    }

    private static String fingerprint(
            String dimensionId,
            BlockPos anchor,
            InventoryMenuSnapshot inventory,
            BlockTargetFingerprint support,
            BlockTargetFingerprint body,
            BlockTargetFingerprint head,
            List<BlockTargetFingerprint> workstations) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateString(digest, FORMAT);
            updateString(digest, dimensionId);
            updateInt(digest, anchor.getX());
            updateInt(digest, anchor.getY());
            updateInt(digest, anchor.getZ());
            updateInt(digest, inventory.selectedHotbar());
            updateItem(digest, inventory.cursor());
            updateInt(digest, inventory.inventorySlots().size());
            for (ItemStackFingerprint item : inventory.inventorySlots()) {
                updateItem(digest, item);
            }
            updateBlock(digest, support);
            updateBlock(digest, body);
            updateBlock(digest, head);
            updateInt(digest, workstations.size());
            for (BlockTargetFingerprint workstation : workstations) {
                updateBlock(digest, workstation);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateItem(
            MessageDigest digest, ItemStackFingerprint item) {
        updateBoolean(digest, item.itemId().isPresent());
        item.itemId().ifPresent(value -> updateString(digest, value.value()));
        updateInt(digest, item.count());
        updateInt(digest, item.damage());
        updateBoolean(digest, item.componentsDigest().isPresent());
        item.componentsDigest().ifPresent(value -> updateString(digest, value));
    }

    private static void updateBlock(
            MessageDigest digest, BlockTargetFingerprint block) {
        updateString(digest, block.dimension().value());
        updateInt(digest, block.position().x());
        updateInt(digest, block.position().y());
        updateInt(digest, block.position().z());
        updateString(digest, block.state().blockId().value());
        updateInt(digest, block.state().properties().size());
        for (Map.Entry<String, String> property : block.state().properties()
                .entrySet()) {
            updateString(digest, property.getKey());
            updateString(digest, property.getValue());
        }
    }

    private static void updateBoolean(MessageDigest digest, boolean value) {
        digest.update((byte) (value ? 1 : 0));
    }

    private static void updateString(MessageDigest digest, String value) {
        byte[] bytes = Objects.requireNonNull(value, "value")
                .getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static void requireTick(long observedTick) {
        if (observedTick < 0L) {
            throw new IllegalArgumentException(
                    "observedTick must be non-negative");
        }
    }

    private record Observation(
            String dimensionId, BlockPos anchor, String fingerprint) {
        private Observation {
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(anchor, "anchor");
            CheckpointNbt.requireSha256(fingerprint, "fingerprint");
        }
    }
}
