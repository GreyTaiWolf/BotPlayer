package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
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
