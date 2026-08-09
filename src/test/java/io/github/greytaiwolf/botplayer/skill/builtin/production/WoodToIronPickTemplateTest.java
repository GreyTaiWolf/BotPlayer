package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import org.junit.jupiter.api.Test;

class WoodToIronPickTemplateTest {
    @Test
    void boundedDagResolvesToIronPickWithExactMenuContracts() {
        ProductionPlanValidation validation = ProductionPlanValidator.p5aDefault()
                .validate(WoodToIronPickTemplate.create());

        assertTrue(validation.accepted(), () -> validation.violations().toString());
        assertEquals(12, validation.resolvedNodes().size());
        assertEquals(12, validation.usage().menuTransactions());
        assertEquals(122, validation.usage().menuClicks());
        assertEquals(3, validation.usage().furnaceInputItems());
        assertEquals(1, validation.projectedFinalLedger().quantityOf(
                ProductionMaterials.IRON_PICKAXE));
        assertEquals(MenuFamily.INVENTORY_2X2, validation.resolvedNodes()
                .stream()
                .filter(node -> node.node().nodeId().equals("craft_planks"))
                .findFirst()
                .orElseThrow()
                .menuContract()
                .orElseThrow()
                .family());
        ProductionResolvedNode smelt = validation.resolvedNodes().stream()
                .filter(node -> node.node().nodeId().equals("smelt_iron"))
                .findFirst()
                .orElseThrow();
        assertEquals(MenuFamily.FURNACE, smelt.menuContract().orElseThrow()
                .family());
        assertEquals(3, smelt.furnaceRequirement().orElseThrow()
                .inputCount());
        assertEquals(FurnaceFuel.COAL, smelt.furnaceRequirement().orElseThrow()
                .fuel());
    }
}
