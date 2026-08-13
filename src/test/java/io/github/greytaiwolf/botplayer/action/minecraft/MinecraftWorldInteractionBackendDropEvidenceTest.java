package io.github.greytaiwolf.botplayer.action.minecraft;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionBackend;
import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftWorldInteractionBackendDropEvidenceTest {
    @Test
    void breakBlockIsRevalidatedAtActionStart() {
        assertTrue(MinecraftWorldInteractionBackend
                .requiresStartRevalidation(new WorldInteractionActionSpec
                        .BreakBlock(new BlockHitTarget(
                                new BlockTargetFingerprint(
                                        new ResourceId("minecraft:overworld"),
                                        new BlockCoordinates(1, 64, 1),
                                        new BlockStateFingerprint(
                                                new ResourceId("minecraft:sugar_cane"),
                                                Map.of())),
                                BlockHitTarget.Face.UP,
                                0.5D,
                                1.0D,
                                0.5D,
                                false),
                                ItemStackFingerprint.empty())));
    }

    @Test
    void attackEntityIsRevalidatedAtActionStart() {
        assertTrue(MinecraftWorldInteractionBackend
                .requiresStartRevalidation(new WorldInteractionActionSpec
                        .AttackEntity(new EntityTargetFingerprint(
                                new ResourceId("minecraft:overworld"),
                                new UUID(1L, 2L),
                                new ResourceId("minecraft:zombie")))));
    }

    @Test
    void threeDropWheatReceiptUsesThreeCompactEvidenceItems() {
        assertCompactWheatReceiptCount(3);
    }

    @Test
    void fourDropWheatReceiptUsesFourCompactEvidenceItems() {
        assertCompactWheatReceiptCount(4);
    }

    @Test
    void singletonDropRetainsLegacyP5aEvidenceProtocol() {
        List<ActionEvidence> evidence = MinecraftWorldInteractionBackend
                .breakDropEvidence(List.of(new BreakDropProvenanceCapture
                        .Provenance(new UUID(1L, 1L),
                                "minecraft:oak_log", 1)))
                .orElseThrow();

        assertEquals(List.of(
                        "block.drop.entity.id",
                        "block.drop.item",
                        "block.drop.count"),
                evidence.stream().map(ActionEvidence::key).toList());
        assertOutcomeFits(evidence);
    }

    @Test
    void boundedProtocolNeverReturnsPartialEvidenceBeyondOutcomeLimit() {
        List<BreakDropProvenanceCapture.Provenance> maximum = new ArrayList<>();
        for (int index = 1;
                index <= ActionOutcome.MAX_EVIDENCE_ITEMS - 3;
                index++) {
            maximum.add(new BreakDropProvenanceCapture.Provenance(
                    new UUID(7L, index), "minecraft:stone", 1));
        }

        List<ActionEvidence> evidence = MinecraftWorldInteractionBackend
                .breakDropEvidence(maximum).orElseThrow();
        assertEquals(ActionOutcome.MAX_EVIDENCE_ITEMS - 3,
                evidence.size());
        assertOutcomeFits(evidence);

        List<BreakDropProvenanceCapture.Provenance> overflow =
                new ArrayList<>(maximum);
        overflow.add(new BreakDropProvenanceCapture.Provenance(
                new UUID(7L, 99L), "minecraft:stone", 1));
        assertTrue(MinecraftWorldInteractionBackend
                .breakDropEvidence(overflow).isEmpty());
    }

    @Test
    void duplicateDropEntityIdRejectsTheEntireReceiptSet() {
        UUID duplicate = new UUID(7L, 77L);
        assertTrue(MinecraftWorldInteractionBackend.breakDropEvidence(List.of(
                        new BreakDropProvenanceCapture.Provenance(
                                duplicate, "minecraft:wheat", 1),
                        new BreakDropProvenanceCapture.Provenance(
                                duplicate, "minecraft:wheat_seeds", 1)))
                .isEmpty());
    }

    @Test
    void compactReceiptParserRejectsNonCanonicalFields() {
        UUID entityId = new UUID(8L, 9L);
        String valid = entityId + "|minecraft:wheat_seeds|2";

        assertEquals(new BreakDropProvenanceCapture.Provenance(
                        entityId, "minecraft:wheat_seeds", 2),
                BreakDropProvenanceCapture.parseCompactReceipt(valid)
                        .orElseThrow());
        assertTrue(BreakDropProvenanceCapture.parseCompactReceipt(
                entityId + "|minecraft:wheat_seeds|02").isEmpty());
        assertFalse(BreakDropProvenanceCapture.parseCompactReceipt(
                entityId + "|minecraft:wheat_seeds|2|extra").isPresent());
    }

    private static void assertCompactWheatReceiptCount(int count) {
        List<BreakDropProvenanceCapture.Provenance> drops =
                matureWheatDrops(count);
        List<ActionEvidence> evidence = MinecraftWorldInteractionBackend
                .breakDropEvidence(drops).orElseThrow();

        assertEquals(count, evidence.size());
        assertTrue(evidence.stream().allMatch(receipt ->
                receipt.key().equals(BreakDropProvenanceCapture
                        .COMPACT_RECEIPT_EVIDENCE_KEY)));
        for (int index = 0; index < count; index++) {
            assertEquals(drops.get(index),
                    BreakDropProvenanceCapture.parseCompactReceipt(
                            evidence.get(index).value()).orElseThrow());
        }
        assertOutcomeFits(evidence);
    }

    private static List<BreakDropProvenanceCapture.Provenance>
            matureWheatDrops(int count) {
        if (count < 2 || count > 4) {
            throw new IllegalArgumentException(
                    "mature wheat test receipt count must be 2..4");
        }
        List<BreakDropProvenanceCapture.Provenance> drops =
                new ArrayList<>(count);
        drops.add(new BreakDropProvenanceCapture.Provenance(
                new UUID(3L, 1L), "minecraft:wheat", 1));
        for (int index = 1; index < count; index++) {
            drops.add(new BreakDropProvenanceCapture.Provenance(
                    new UUID(3L, index + 1L), "minecraft:wheat_seeds", 1));
        }
        return List.copyOf(drops);
    }

    private static void assertOutcomeFits(List<ActionEvidence> receipts) {
        List<ActionEvidence> all = new ArrayList<>(List.of(
                new ActionEvidence("block.position", "1,2,3"),
                new ActionEvidence("block.after", "minecraft:air"),
                new ActionEvidence("inventory.changed", "false")));
        all.addAll(receipts);
        ActionBackend.BackendResult backend = new ActionBackend.BackendResult(
                new UUID(10L, 11L),
                new UUID(11L, 12L),
                1L,
                ActionBackend.BackendStep.SUCCEEDED,
                ActionFailureCode.NONE,
                all,
                "Verified block break");
        ActionOutcome outcome = new ActionOutcome(
                new UUID(11L, 12L),
                ActionState.SUCCEEDED,
                ActionFailureCode.NONE,
                0L,
                1L,
                all,
                "Verified block break");
        assertEquals(all, backend.evidence());
        assertTrue(outcome.evidence().size()
                <= ActionOutcome.MAX_EVIDENCE_ITEMS);
    }
}
