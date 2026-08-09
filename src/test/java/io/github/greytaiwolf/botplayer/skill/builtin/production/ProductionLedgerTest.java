package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionLedgerTest {
    @Test
    void deltaRequiresWholeDebitAndNeverProducesPartialBalance() {
        ProductionLedger before = new ProductionLedger(Map.of(
                ProductionMaterials.OAK_LOG, 1,
                ProductionMaterials.STICK, 2));
        ProductionDelta delta = new ProductionDelta(
                ProductionLedger.of(ProductionMaterials.OAK_LOG, 1),
                ProductionLedger.of(ProductionMaterials.OAK_PLANKS, 4));

        ProductionLedger after = delta.applyTo(before).orElseThrow();

        assertEquals(0, after.quantityOf(ProductionMaterials.OAK_LOG));
        assertEquals(4, after.quantityOf(ProductionMaterials.OAK_PLANKS));
        assertEquals(2, after.quantityOf(ProductionMaterials.STICK));
        assertFalse(before.trySubtract(ProductionLedger.of(
                ProductionMaterials.OAK_LOG, 2)).isPresent());
    }

    @Test
    void ledgerAndDeltaRejectAmbiguousOrInvalidAmounts() {
        assertThrows(IllegalArgumentException.class, () ->
                new ProductionLedger(Map.of(ProductionMaterials.COAL, 0)));
        assertThrows(IllegalArgumentException.class, () ->
                new ProductionDelta(
                        ProductionLedger.of(ProductionMaterials.COAL, 1),
                        ProductionLedger.of(ProductionMaterials.COAL, 1)));
        assertTrue(ProductionLedger.empty().isEmpty());
    }
}
