package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import org.junit.jupiter.api.Test;

class WoodToIronPickTemplateTest {
    @Test
    void boundedDagResolvesToIronPickWithExactMenuContracts() {
        ProductionPlanTemplate template = WoodToIronPickTemplate.create();
        ProductionPlanValidation validation = ProductionPlanValidator.p5aDefault()
                .validate(template);

        assertTrue(validation.accepted(), () -> validation.violations().toString());
        assertEquals(14, validation.resolvedNodes().size());
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
        assertEquals(new ProductionDelta(
                        ProductionLedger.of(
                                ProductionMaterials.CRAFTING_TABLE, 1),
                        ProductionLedger.empty()), validation.resolvedNodes()
                .stream()
                .filter(node -> node.node().nodeId().equals(
                        "place_crafting_table"))
                .findFirst()
                .orElseThrow()
                .expectedPlayerDelta());
        assertEquals(new ProductionDelta(
                        ProductionLedger.of(ProductionMaterials.FURNACE, 1),
                        ProductionLedger.empty()), validation.resolvedNodes()
                .stream()
                .filter(node -> node.node().nodeId().equals("place_furnace"))
                .findFirst()
                .orElseThrow()
                .expectedPlayerDelta());
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "crafting_table", "place_crafting_table")));
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "place_crafting_table", "wooden_pickaxe")));
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "place_crafting_table", "craft_furnace")));
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "place_crafting_table", "stone_pickaxe")));
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "place_crafting_table", "iron_pickaxe")));
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "craft_furnace", "place_furnace")));
        assertTrue(template.edges().contains(new ProductionPlanEdge(
                "place_furnace", "smelt_iron")));
        assertFalse(template.edges().contains(new ProductionPlanEdge(
                "crafting_table", "wooden_pickaxe")));
        assertFalse(template.edges().contains(new ProductionPlanEdge(
                "craft_furnace", "smelt_iron")));
    }
}
