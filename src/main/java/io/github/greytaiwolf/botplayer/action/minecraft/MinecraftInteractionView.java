package io.github.greytaiwolf.botplayer.action.minecraft;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Stateless conversion and fingerprint helpers for P2-C.
 *
 * <p>Every method consumes a short-lived server-thread object and returns immutable values only.
 */
final class MinecraftInteractionView {
    private MinecraftInteractionView() {}

    static ResourceId dimension(BotServerPlayer player) {
        return new ResourceId(
                player.serverLevel().dimension().location().toString());
    }

    static BlockPos position(BlockCoordinates position) {
        return new BlockPos(position.x(), position.y(), position.z());
    }

    static Direction direction(BlockHitTarget.Face face) {
        return switch (face) {
            case DOWN -> Direction.DOWN;
            case UP -> Direction.UP;
            case NORTH -> Direction.NORTH;
            case SOUTH -> Direction.SOUTH;
            case WEST -> Direction.WEST;
            case EAST -> Direction.EAST;
        };
    }

    static InteractionHand hand(WorldInteractionActionSpec.Hand hand) {
        return switch (hand) {
            case MAIN_HAND -> InteractionHand.MAIN_HAND;
            case OFF_HAND -> InteractionHand.OFF_HAND;
        };
    }

    static BlockHitResult hit(BlockHitTarget target) {
        return new BlockHitResult(
                new Vec3(
                        target.worldX(),
                        target.worldY(),
                        target.worldZ()),
                direction(target.face()),
                position(target.target().position()),
                target.inside());
    }

    static BlockTargetFingerprint blockFingerprint(
            BotServerPlayer player, BlockPos position) {
        BlockState state = player.serverLevel().getBlockState(position);
        ResourceId blockId = new ResourceId(
                BuiltInRegistries.BLOCK
                        .getKey(state.getBlock())
                        .toString());
        Map<String, String> properties = new TreeMap<>();
        state.getValues().forEach((property, value) ->
                properties.put(property.getName(), value.toString()));
        return new BlockTargetFingerprint(
                dimension(player),
                new BlockCoordinates(
                        position.getX(),
                        position.getY(),
                        position.getZ()),
                new BlockStateFingerprint(blockId, properties));
    }

    static Optional<Entity> entity(
            BotServerPlayer player, EntityTargetFingerprint expected) {
        if (!dimension(player).equals(expected.dimension())) {
            return Optional.empty();
        }
        Entity entity = player.serverLevel().getEntity(expected.entityId());
        if (entity == null
                || entity.isRemoved()
                || !new ResourceId(
                                BuiltInRegistries.ENTITY_TYPE
                                        .getKey(entity.getType())
                                        .toString())
                        .equals(expected.entityType())) {
            return Optional.empty();
        }
        return Optional.of(entity);
    }

    static ItemStackFingerprint itemFingerprint(
            BotServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return ItemStackFingerprint.empty();
        }
        String itemId =
                BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
        MessageDigest digest = newDigest();
        updateCanonicalTag(
                digest,
                stack.copyWithCount(1).save(player.registryAccess()));
        return ItemStackFingerprint.of(
                new ResourceId(itemId),
                stack.getCount(),
                stack.getDamageValue(),
                HexFormat.of().formatHex(digest.digest()));
    }

    static String inventoryDigest(BotServerPlayer player) {
        MessageDigest digest = newDigest();
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            digest.update((byte) slot);
            digest.update(
                    itemFingerprint(
                                    player,
                                    player.getInventory().getItem(slot))
                            .toString()
                            .getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static int inventoryCount(
            BotServerPlayer player, ItemStackFingerprint expectedItem) {
        Objects.requireNonNull(expectedItem, "expectedItem");
        int matchingCount = 0;
        int size = player.getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStackFingerprint actual = itemFingerprint(
                    player, player.getInventory().getItem(slot));
            if (actual.sameItemAndComponents(expectedItem)) {
                matchingCount = Math.addExact(
                        matchingCount, actual.count());
            }
        }
        return matchingCount;
    }

    static boolean canReachAndSeeBlock(
            BotServerPlayer player, BlockHitTarget target) {
        BlockReachEvidence evidence =
                blockReachEvidence(player, target);
        return evidence.withinReach() && evidence.rayHitTarget();
    }

    static BlockReachEvidence blockReachEvidence(
            BotServerPlayer player, BlockHitTarget target) {
        BlockPos targetPosition =
                position(target.target().position());
        boolean withinReach =
                player.canInteractWithBlock(targetPosition, 0.0D);
        Vec3 eye = player.getEyePosition();
        Vec3 expectedHit = new Vec3(
                target.worldX(),
                target.worldY(),
                target.worldZ());
        Vec3 sight = expectedHit.subtract(eye);
        if (sight.lengthSqr() <= 1.0E-12D) {
            return new BlockReachEvidence(
                    withinReach,
                    false,
                    "degenerate",
                    "none",
                    vector(eye),
                    vector(expectedHit),
                    vector(expectedHit));
        }

        /*
         * Clip treats an end point on a face as an exclusive boundary. Continue a bounded
         * distance through the declared point so the first visible block validates the target.
         * The packet hit itself remains unchanged.
         */
        Vec3 rayEnd = expectedHit.add(sight.normalize());
        HitResult result = player.serverLevel().clip(new ClipContext(
                eye,
                rayEnd,
                ClipContext.Block.OUTLINE,
                ClipContext.Fluid.NONE,
                player));
        boolean rayHitTarget =
                result.getType() == HitResult.Type.BLOCK
                        && result instanceof BlockHitResult blockHit
                        && blockHit.getBlockPos()
                                .equals(targetPosition);
        String rayHitPosition =
                result instanceof BlockHitResult blockHit
                        ? blockPosition(blockHit.getBlockPos())
                        : "none";
        return new BlockReachEvidence(
                withinReach,
                rayHitTarget,
                result.getType().name().toLowerCase(Locale.ROOT),
                rayHitPosition,
                vector(eye),
                vector(expectedHit),
                vector(rayEnd));
    }

    record BlockReachEvidence(
            boolean withinReach,
            boolean rayHitTarget,
            String rayHitType,
            String rayHitPosition,
            String eyePosition,
            String expectedHitPosition,
            String rayEndPosition) {
        BlockReachEvidence {
            Objects.requireNonNull(rayHitType, "rayHitType");
            Objects.requireNonNull(rayHitPosition, "rayHitPosition");
            Objects.requireNonNull(eyePosition, "eyePosition");
            Objects.requireNonNull(
                    expectedHitPosition, "expectedHitPosition");
            Objects.requireNonNull(rayEndPosition, "rayEndPosition");
        }
    }

    private static String blockPosition(BlockPos position) {
        return position.getX()
                + ","
                + position.getY()
                + ","
                + position.getZ();
    }

    private static String vector(Vec3 vector) {
        return String.format(
                Locale.ROOT,
                "%.6f,%.6f,%.6f",
                vector.x,
                vector.y,
                vector.z);
    }

    /**
     * Hashes an NBT tree with compound keys in sorted order. Lists retain semantic order.
     */
    private static void updateCanonicalTag(
            MessageDigest digest, Tag tag) {
        digest.update(tag.getId());
        if (tag instanceof CompoundTag compound) {
            List<String> keys =
                    new ArrayList<>(compound.getAllKeys());
            keys.sort(String::compareTo);
            updateInt(digest, keys.size());
            for (String key : keys) {
                updateString(digest, key);
                updateCanonicalTag(
                        digest,
                        Objects.requireNonNull(
                                compound.get(key),
                                "compound value"));
            }
            return;
        }
        if (tag instanceof ListTag list) {
            updateInt(digest, list.size());
            for (Tag child : list) {
                updateCanonicalTag(digest, child);
            }
            return;
        }
        updateString(digest, tag.toString());
    }

    private static void updateString(
            MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        updateInt(digest, bytes.length);
        digest.update(bytes);
    }

    private static void updateInt(MessageDigest digest, int value) {
        digest.update((byte) (value >>> 24));
        digest.update((byte) (value >>> 16));
        digest.update((byte) (value >>> 8));
        digest.update((byte) value);
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 is unavailable", exception);
        }
    }
}
