package io.github.greytaiwolf.botplayer.skill.builtin.farming;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.ActionEvidence;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SugarCaneHarvestEvidenceTest {
    private static final UUID DROP_ID = new UUID(87L, 19L);

    @Test
    void acceptsOnlyTheCanonicalOneCaneLegacyReceipt() {
        List<ActionEvidence> evidence = List.of(
                new ActionEvidence("block.position", "12,64,-8"),
                new ActionEvidence("block.after", "minecraft:air"),
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.item", "minecraft:sugar_cane"),
                new ActionEvidence("block.drop.count", "1"));

        assertEquals(DROP_ID, SugarCaneHarvestEvidence.exactSingleDrop(
                evidence).orElseThrow().entityId());
    }

    @Test
    void rejectsAmbiguousOrWidenedDropProof() {
        assertTrue(SugarCaneHarvestEvidence.exactSingleDrop(List.of(
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.item", "minecraft:sugar_cane"),
                new ActionEvidence("block.drop.count", "1"),
                new ActionEvidence("block.drop.receipt",
                        DROP_ID + "|minecraft:sugar_cane|1"))).isEmpty());
        assertTrue(SugarCaneHarvestEvidence.exactSingleDrop(List.of(
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.item", "minecraft:sugar_cane"),
                new ActionEvidence("block.drop.count", "2"))).isEmpty());
        assertTrue(SugarCaneHarvestEvidence.exactSingleDrop(List.of(
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.item", "minecraft:bamboo"),
                new ActionEvidence("block.drop.count", "1"))).isEmpty());
        assertTrue(SugarCaneHarvestEvidence.exactSingleDrop(List.of(
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.item", "minecraft:sugar_cane"),
                new ActionEvidence("block.drop.count", "1"))).isEmpty());
        assertTrue(SugarCaneHarvestEvidence.exactSingleDrop(List.of(
                new ActionEvidence("block.drop.entity.id", DROP_ID.toString()),
                new ActionEvidence("block.drop.item", "minecraft:sugar_cane"),
                new ActionEvidence("block.drop.count", "+1"))).isEmpty());
    }
}
