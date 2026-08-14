package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.FurnaceKind;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlaceWorkstationTest {
    @Test
    void eachClosedWorkstationConsumesExactlyOneMatchingMaterial() {
        assertEquals(new ProductionDelta(
                        ProductionLedger.of(
                                ProductionMaterials.CRAFTING_TABLE, 1),
                        ProductionLedger.empty()),
                new PlaceWorkstation(WorkstationKind.CRAFTING_TABLE)
                        .expectedDelta());
        assertEquals(new ProductionDelta(
                        ProductionLedger.of(ProductionMaterials.FURNACE, 1),
                        ProductionLedger.empty()),
                new PlaceWorkstation(WorkstationKind.FURNACE)
                        .expectedDelta());
        assertEquals(new ProductionDelta(
                        ProductionLedger.of(
                                ProductionMaterials.BLAST_FURNACE, 1),
                        ProductionLedger.empty()),
                new PlaceWorkstation(WorkstationKind.BLAST_FURNACE)
                        .expectedDelta());
        assertEquals(new ProductionDelta(
                        ProductionLedger.of(ProductionMaterials.SMOKER, 1),
                        ProductionLedger.empty()),
                new PlaceWorkstation(WorkstationKind.SMOKER)
                        .expectedDelta());
    }

    @Test
    void workstationStateShapesAreClosedAndComplete() {
        BlockStateFingerprint table = WorkstationKind.CRAFTING_TABLE
                .expectedPlacedState("north");
        BlockStateFingerprint furnace = WorkstationKind.FURNACE
                .expectedPlacedState("east");
        BlockStateFingerprint blastFurnace = WorkstationKind.BLAST_FURNACE
                .expectedPlacedState("north");
        BlockStateFingerprint smoker = WorkstationKind.SMOKER
                .expectedPlacedState("south");

        assertTrue(WorkstationKind.CRAFTING_TABLE
                .matchesExpectedPlacedState(table));
        assertTrue(WorkstationKind.FURNACE
                .matchesExpectedPlacedState(furnace));
        assertTrue(WorkstationKind.BLAST_FURNACE
                .matchesExpectedPlacedState(blastFurnace));
        assertTrue(WorkstationKind.SMOKER
                .matchesExpectedPlacedState(smoker));
        assertEquals(java.util.Optional.empty(), WorkstationKind.CRAFTING_TABLE
                .furnaceKind());
        assertEquals(java.util.Optional.of(FurnaceKind.FURNACE),
                WorkstationKind.FURNACE.furnaceKind());
        assertEquals(java.util.Optional.of(FurnaceKind.BLAST_FURNACE),
                WorkstationKind.BLAST_FURNACE.furnaceKind());
        assertEquals(java.util.Optional.of(FurnaceKind.SMOKER),
                WorkstationKind.SMOKER.furnaceKind());
        assertFalse(WorkstationKind.FURNACE.matchesExpectedPlacedState(
                new BlockStateFingerprint(
                        new ResourceId("minecraft:furnace"),
                        Map.of("facing", "east"))));
        assertFalse(WorkstationKind.FURNACE.matchesExpectedPlacedState(
                new BlockStateFingerprint(
                        new ResourceId("minecraft:furnace"),
                        Map.of("facing", "east", "lit", "true"))));
        assertFalse(WorkstationKind.BLAST_FURNACE
                .matchesExpectedPlacedState(furnace));
        assertFalse(WorkstationKind.SMOKER
                .matchesExpectedPlacedState(blastFurnace));
        assertThrows(IllegalArgumentException.class,
                () -> WorkstationKind.FURNACE.expectedPlacedState("up"));
    }
}
