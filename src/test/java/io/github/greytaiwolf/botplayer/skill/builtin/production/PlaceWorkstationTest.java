package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
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
    }

    @Test
    void workstationStateShapesAreClosedAndComplete() {
        BlockStateFingerprint table = WorkstationKind.CRAFTING_TABLE
                .expectedPlacedState("north");
        BlockStateFingerprint furnace = WorkstationKind.FURNACE
                .expectedPlacedState("east");

        assertTrue(WorkstationKind.CRAFTING_TABLE
                .matchesExpectedPlacedState(table));
        assertTrue(WorkstationKind.FURNACE
                .matchesExpectedPlacedState(furnace));
        assertFalse(WorkstationKind.FURNACE.matchesExpectedPlacedState(
                new BlockStateFingerprint(
                        new ResourceId("minecraft:furnace"),
                        Map.of("facing", "east"))));
        assertFalse(WorkstationKind.FURNACE.matchesExpectedPlacedState(
                new BlockStateFingerprint(
                        new ResourceId("minecraft:furnace"),
                        Map.of("facing", "east", "lit", "true"))));
        assertThrows(IllegalArgumentException.class,
                () -> WorkstationKind.FURNACE.expectedPlacedState("up"));
    }
}
