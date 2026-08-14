package io.github.greytaiwolf.botplayer.action.minecraft;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.Test;

/** Locks the legacy block-position ray boundary apart from strict atomic placement. */
class MinecraftInteractionViewTest {
    @Test
    void legacyReachKeepsPositionOnlyWhileAimAndPlaceRequiresFaceAndPoint() {
        BlockHitTarget frozenEastFace = target();
        BlockHitResult westFaceAtCenter = new BlockHitResult(
                new Vec3(4.5D, 64.5D, 5.5D),
                Direction.WEST,
                new BlockPos(4, 64, 5),
                false);

        assertTrue(MinecraftInteractionView.matchesBlockRayHit(
                frozenEastFace, westFaceAtCenter, false));
        assertFalse(MinecraftInteractionView.matchesBlockRayHit(
                frozenEastFace, westFaceAtCenter, true));
    }

    @Test
    void aimAndPlaceAcceptsOnlyTheFrozenFaceAndPoint() {
        BlockHitTarget frozenEastFace = target();
        BlockHitResult exactEastFace = new BlockHitResult(
                new Vec3(5.0D, 64.5D, 5.5D),
                Direction.EAST,
                new BlockPos(4, 64, 5),
                false);

        assertTrue(MinecraftInteractionView.matchesBlockRayHit(
                frozenEastFace, exactEastFace, true));
    }

    @Test
    void aimAndPlaceRejectsAnotherPointOnTheSameFrozenFace() {
        BlockHitTarget frozenEastFace = target();
        BlockHitResult shiftedEastFace = new BlockHitResult(
                new Vec3(5.0D, 64.6D, 5.5D),
                Direction.EAST,
                new BlockPos(4, 64, 5),
                false);

        assertTrue(MinecraftInteractionView.matchesBlockRayHit(
                frozenEastFace, shiftedEastFace, false));
        assertFalse(MinecraftInteractionView.matchesBlockRayHit(
                frozenEastFace, shiftedEastFace, true));
    }

    private static BlockHitTarget target() {
        return new BlockHitTarget(
                new BlockTargetFingerprint(
                        new ResourceId("minecraft:overworld"),
                        new BlockCoordinates(4, 64, 5),
                        new BlockStateFingerprint(
                                new ResourceId("minecraft:stone"), Map.of())),
                BlockHitTarget.Face.EAST,
                1.0D,
                0.5D,
                0.5D,
                false);
    }
}
