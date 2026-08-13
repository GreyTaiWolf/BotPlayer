package io.github.greytaiwolf.botplayer.skill.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.minecraft.BreakDropProvenanceCapture;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class MinecraftWheatFarmingSkillNodeHandlerTest {
    @Test
    void parsesOnlyTheExactIntegerTargetSchema() {
        assertEquals(new BlockCoordinates(12, 64, -8),
                MinecraftWheatFarmingSkillNodeHandler.parseTarget(
                        parameters(12, 64, -8)).orElseThrow());

        Map<String, Object> extra = values(12, 64, -8);
        extra.put("target.dimension", "minecraft:overworld");
        assertTrue(MinecraftWheatFarmingSkillNodeHandler.parseTarget(
                new SkillParameters(extra)).isEmpty());

        Map<String, Object> coerced = values(12, 64, -8);
        coerced.put(WheatFarmingSkillIds.TARGET_X_PARAMETER, 12L);
        assertTrue(MinecraftWheatFarmingSkillNodeHandler.parseTarget(
                new SkillParameters(coerced)).isEmpty());

        Map<String, Object> missing = values(12, 64, -8);
        missing.remove(WheatFarmingSkillIds.TARGET_Z_PARAMETER);
        assertTrue(MinecraftWheatFarmingSkillNodeHandler.parseTarget(
                new SkillParameters(missing)).isEmpty());
    }

    @Test
    void decodesThreeAndFourCompactDropReceiptsWithoutLegacyAmbiguity() {
        List<BreakDropProvenanceCapture.Provenance> three =
                wheatReceipts(3);
        List<BreakDropProvenanceCapture.Provenance> four =
                wheatReceipts(4);

        assertEquals(three, MinecraftWheatFarmingSkillNodeHandler
                .harvestDropProvenances(compactEvidence(three))
                .orElseThrow());
        assertEquals(four, MinecraftWheatFarmingSkillNodeHandler
                .harvestDropProvenances(compactEvidence(four))
                .orElseThrow());

        BreakDropProvenanceCapture.Provenance single =
                new BreakDropProvenanceCapture.Provenance(
                        new UUID(6L, 1L), "minecraft:wheat", 1);
        assertEquals(List.of(single), MinecraftWheatFarmingSkillNodeHandler
                .harvestDropProvenances(List.of(
                        new ActionEvidence("block.drop.entity.id",
                                single.entityId().toString()),
                        new ActionEvidence("block.drop.item",
                                single.itemId()),
                        new ActionEvidence("block.drop.count",
                                Integer.toString(single.count()))))
                .orElseThrow());
        assertTrue(MinecraftWheatFarmingSkillNodeHandler
                .harvestDropProvenances(compactEvidence(List.of(single)))
                .isEmpty());

        List<ActionEvidence> mixed = new ArrayList<>(compactEvidence(three));
        mixed.add(new ActionEvidence("block.drop.entity.id",
                single.entityId().toString()));
        assertTrue(MinecraftWheatFarmingSkillNodeHandler
                .harvestDropProvenances(mixed).isEmpty());
    }

    private static List<BreakDropProvenanceCapture.Provenance> wheatReceipts(
            int count) {
        List<BreakDropProvenanceCapture.Provenance> receipts =
                new ArrayList<>(count);
        receipts.add(new BreakDropProvenanceCapture.Provenance(
                new UUID(5L, 1L), "minecraft:wheat", 1));
        for (int index = 1; index < count; index++) {
            receipts.add(new BreakDropProvenanceCapture.Provenance(
                    new UUID(5L, index + 1L),
                    "minecraft:wheat_seeds", 1));
        }
        return List.copyOf(receipts);
    }

    private static List<ActionEvidence> compactEvidence(
            List<BreakDropProvenanceCapture.Provenance> receipts) {
        return receipts.stream().map(receipt -> new ActionEvidence(
                BreakDropProvenanceCapture.COMPACT_RECEIPT_EVIDENCE_KEY,
                receipt.entityId()
                        + "|"
                        + receipt.itemId()
                        + "|"
                        + receipt.count())).toList();
    }

    private static SkillParameters parameters(int x, int y, int z) {
        return new SkillParameters(values(x, y, z));
    }

    private static Map<String, Object> values(int x, int y, int z) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(WheatFarmingSkillIds.TARGET_X_PARAMETER, x);
        values.put(WheatFarmingSkillIds.TARGET_Y_PARAMETER, y);
        values.put(WheatFarmingSkillIds.TARGET_Z_PARAMETER, z);
        return values;
    }
}
