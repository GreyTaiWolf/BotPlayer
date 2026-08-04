package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.InventoryContentsSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Public server-thread capture boundary for immutable P2 action preconditions.
 */
public final class MinecraftActionSnapshot {
    private MinecraftActionSnapshot() {}

    public static ItemStackFingerprint item(
            BotServerPlayer player, ItemStack stack) {
        requireServerThread(player);
        return MinecraftInteractionView.itemFingerprint(
                player, Objects.requireNonNull(stack, "stack"));
    }

    public static ItemStackFingerprint selectedItem(
            BotServerPlayer player) {
        requireServerThread(player);
        return MinecraftInteractionView.itemFingerprint(
                player, player.getInventory().getSelected());
    }

    public static String inventoryMultiset(
            BotServerPlayer player) {
        requireServerThread(player);
        return MinecraftInteractionView.inventoryMultisetDigest(
                player);
    }

    public static InventoryContentsSnapshot inventoryContents(
            BotServerPlayer player) {
        requireServerThread(player);
        return MinecraftInteractionView.inventoryContents(
                player);
    }

    /**
     * 捕获原生玩家背包菜单及 41 个玩家库存槽，不接受世界容器或自定义查看菜单。
     */
    public static InventoryMenuSnapshot inventoryMenu(
            BotServerPlayer player) {
        requireServerThread(player);
        if (player.containerMenu != player.inventoryMenu) {
            throw new IllegalStateException(
                    "native player inventory menu must be active");
        }
        List<ItemStackFingerprint> slots = new ArrayList<>(
                PlayerInventoryMenuLayout.INVENTORY_SLOT_COUNT);
        for (int inventorySlot = 0;
                inventorySlot
                        < PlayerInventoryMenuLayout
                                .INVENTORY_SLOT_COUNT;
                inventorySlot++) {
            slots.add(MinecraftInteractionView.itemFingerprint(
                    player,
                    player.getInventory().getItem(inventorySlot)));
        }
        return new InventoryMenuSnapshot(
                player.inventoryMenu.containerId,
                player.inventoryMenu.getStateId(),
                player.getInventory().selected,
                MinecraftInteractionView.itemFingerprint(
                        player,
                        player.inventoryMenu.getCarried()),
                List.copyOf(slots));
    }

    public static int inventoryCount(
            BotServerPlayer player,
            ItemStackFingerprint expectedItem) {
        requireServerThread(player);
        return MinecraftInteractionView.inventoryCount(
                player,
                Objects.requireNonNull(
                        expectedItem, "expectedItem"));
    }

    public static BlockTargetFingerprint block(
            BotServerPlayer player, BlockPos position) {
        requireServerThread(player);
        return MinecraftInteractionView.blockFingerprint(
                player, Objects.requireNonNull(position, "position"));
    }

    public static BlockHitTarget blockHit(
            BotServerPlayer player, BlockHitResult hit) {
        requireServerThread(player);
        Objects.requireNonNull(hit, "hit");
        BlockPos position = hit.getBlockPos();
        Vec3 location = hit.getLocation();
        return new BlockHitTarget(
                MinecraftInteractionView.blockFingerprint(
                        player, position),
                switch (hit.getDirection()) {
                    case DOWN -> BlockHitTarget.Face.DOWN;
                    case UP -> BlockHitTarget.Face.UP;
                    case NORTH -> BlockHitTarget.Face.NORTH;
                    case SOUTH -> BlockHitTarget.Face.SOUTH;
                    case WEST -> BlockHitTarget.Face.WEST;
                    case EAST -> BlockHitTarget.Face.EAST;
                },
                localCoordinate(location.x - position.getX()),
                localCoordinate(location.y - position.getY()),
                localCoordinate(location.z - position.getZ()),
                hit.isInside());
    }

    public static EntityTargetFingerprint entity(
            BotServerPlayer player, Entity entity) {
        requireServerThread(player);
        Objects.requireNonNull(entity, "entity");
        if (entity.level() != player.serverLevel()
                || entity.isRemoved()) {
            throw new IllegalArgumentException(
                    "entity must be live in the bot's current server level");
        }
        return new EntityTargetFingerprint(
                MinecraftInteractionView.dimension(player),
                entity.getUUID(),
                new ResourceId(
                        BuiltInRegistries.ENTITY_TYPE
                                .getKey(entity.getType())
                                .toString()));
    }

    public static BlockCoordinates coordinates(BlockPos position) {
        Objects.requireNonNull(position, "position");
        return new BlockCoordinates(
                position.getX(),
                position.getY(),
                position.getZ());
    }

    private static void requireServerThread(
            BotServerPlayer player) {
        Objects.requireNonNull(player, "player");
        MinecraftServer server = player.getServer();
        if (server == null || !server.isSameThread()) {
            throw new IllegalStateException(
                    "Minecraft action snapshots require the authoritative server thread");
        }
    }

    private static double localCoordinate(double value) {
        if (!Double.isFinite(value)
                || value < -1.0E-7D
                || value > 1.0000001D) {
            throw new IllegalArgumentException(
                    "block hit location must lie on or inside its target block");
        }
        return Math.clamp(value, 0.0D, 1.0D);
    }
}
