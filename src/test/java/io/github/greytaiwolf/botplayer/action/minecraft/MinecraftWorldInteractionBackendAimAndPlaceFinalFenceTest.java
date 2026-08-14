package io.github.greytaiwolf.botplayer.action.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionBackend.BackendResult;
import io.github.greytaiwolf.botplayer.action.ActionBackend.BackendStep;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Regression coverage for the atomic post-look, pre-packet fence. */
class MinecraftWorldInteractionBackendAimAndPlaceFinalFenceTest {
    private static final BackendResult ACCEPTED = backendResult(
            BackendStep.ACCEPTED, ActionFailureCode.NONE, "");
    private static final BackendResult REVALIDATION_REJECTED = backendResult(
            BackendStep.FAILED,
            ActionFailureCode.PRECONDITION_FAILED,
            "frozen fact drifted");

    @Test
    void aimAndPlaceBlockIsRevalidatedAtActionStart() {
        assertTrue(MinecraftWorldInteractionBackend.requiresStartRevalidation(
                action()));
    }

    @Test
    void revalidatesAfterLookAndNeverDispatchesWhenLookExposesDrift() {
        AtomicBoolean targetBeforeStillMatches = new AtomicBoolean(true);
        AtomicInteger packetCount = new AtomicInteger();
        List<String> sequence = new ArrayList<>();

        MinecraftWorldInteractionBackend.AimAndPlaceFinalFenceResult result =
                MinecraftWorldInteractionBackend
                        .executeAimAndPlaceFinalFence(
                                () -> {
                                    sequence.add("look");
                                    targetBeforeStillMatches.set(false);
                                },
                                () -> {
                                    sequence.add("revalidate");
                                    return targetBeforeStillMatches.get()
                                            ? ACCEPTED
                                            : REVALIDATION_REJECTED;
                                },
                                () -> {
                                    sequence.add("aim");
                                    return 0.0D;
                                },
                                0.5D,
                                ignored -> {
                                    sequence.add("packet");
                                    packetCount.incrementAndGet();
                                });

        assertEquals(
                MinecraftWorldInteractionBackend
                        .AimAndPlaceFinalFenceStatus.REVALIDATION_REJECTED,
                result.status());
        assertSame(REVALIDATION_REJECTED, result.revalidation());
        assertTrue(Double.isNaN(result.finalAimErrorDegrees()));
        assertEquals(List.of("look", "revalidate"), sequence);
        assertEquals(0, packetCount.get());
    }

    @Test
    void neverDispatchesWhenAimFenceRejectsAfterAcceptedRevalidation() {
        AtomicInteger packetCount = new AtomicInteger();
        List<String> sequence = new ArrayList<>();

        MinecraftWorldInteractionBackend.AimAndPlaceFinalFenceResult result =
                MinecraftWorldInteractionBackend
                        .executeAimAndPlaceFinalFence(
                                () -> sequence.add("look"),
                                () -> {
                                    sequence.add("revalidate");
                                    return ACCEPTED;
                                },
                                () -> {
                                    sequence.add("aim");
                                    return 0.500001D;
                                },
                                0.5D,
                                ignored -> {
                                    sequence.add("packet");
                                    packetCount.incrementAndGet();
                                });

        assertEquals(
                MinecraftWorldInteractionBackend
                        .AimAndPlaceFinalFenceStatus.AIM_REJECTED,
                result.status());
        assertSame(ACCEPTED, result.revalidation());
        assertEquals(0.500001D, result.finalAimErrorDegrees());
        assertEquals(List.of("look", "revalidate", "aim"), sequence);
        assertEquals(0, packetCount.get());
    }

    @Test
    void dispatchesOnlyAfterTheFinalFencePassesAndForwardsAimError() {
        AtomicReference<Double> dispatchedAimError = new AtomicReference<>();
        List<String> sequence = new ArrayList<>();

        MinecraftWorldInteractionBackend.AimAndPlaceFinalFenceResult result =
                MinecraftWorldInteractionBackend
                        .executeAimAndPlaceFinalFence(
                                () -> sequence.add("look"),
                                () -> {
                                    sequence.add("revalidate");
                                    return ACCEPTED;
                                },
                                () -> {
                                    sequence.add("aim");
                                    return 0.25D;
                                },
                                0.5D,
                                aimError -> {
                                    sequence.add("packet");
                                    dispatchedAimError.set(aimError);
                                });

        assertEquals(
                MinecraftWorldInteractionBackend
                        .AimAndPlaceFinalFenceStatus.PACKET_DISPATCHED,
                result.status());
        assertSame(ACCEPTED, result.revalidation());
        assertEquals(0.25D, result.finalAimErrorDegrees());
        assertEquals(0.25D, dispatchedAimError.get().doubleValue());
        assertEquals(List.of("look", "revalidate", "aim", "packet"),
                sequence);
    }

    private static WorldInteractionActionSpec.AimAndPlaceBlock action() {
        ResourceId overworld = new ResourceId("minecraft:overworld");
        BlockTargetFingerprint anchor = new BlockTargetFingerprint(
                overworld,
                new BlockCoordinates(4, 64, 5),
                new BlockStateFingerprint(new ResourceId("minecraft:stone"), Map.of()));
        BlockTargetFingerprint targetBefore = new BlockTargetFingerprint(
                overworld,
                new BlockCoordinates(4, 65, 5),
                new BlockStateFingerprint(new ResourceId("minecraft:air"), Map.of()));
        BlockTargetFingerprint expectedPlaced = new BlockTargetFingerprint(
                overworld,
                targetBefore.position(),
                new BlockStateFingerprint(new ResourceId("minecraft:oak_planks"), Map.of()));
        ItemStackFingerprint held = ItemStackFingerprint.of(
                new ResourceId("minecraft:oak_planks"), 1, 0, "0".repeat(64));
        return new WorldInteractionActionSpec.AimAndPlaceBlock(
                new BlockHitTarget(anchor, BlockHitTarget.Face.UP, 0.5D, 1.0D, 0.5D, false),
                targetBefore,
                expectedPlaced,
                held);
    }

    private static BackendResult backendResult(
            BackendStep step,
            ActionFailureCode failureCode,
            String safeSummary) {
        return new BackendResult(
                new UUID(1L, 1L),
                new UUID(2L, 2L),
                1L,
                step,
                failureCode,
                List.of(),
                safeSummary);
    }
}
