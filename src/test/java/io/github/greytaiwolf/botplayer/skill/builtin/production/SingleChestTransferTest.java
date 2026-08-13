package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SingleChestTransferTest {
    @Test
    void chestTransferPreservesBothLedgersAndRequiresExactChestMenu() {
        SingleChestTransfer transfer = new SingleChestTransfer(
                ProductionLedger.of(ProductionMaterials.COBBLESTONE, 8),
                ProductionLedger.of(ProductionMaterials.COAL, 1),
                12);
        ProductionLedger player = new ProductionLedger(Map.of(
                ProductionMaterials.COBBLESTONE, 8,
                ProductionMaterials.STICK, 2));
        ProductionLedger chest = ProductionLedger.of(ProductionMaterials.COAL, 1);

        assertEquals(MenuFamily.CHEST_3X9, transfer.menuContract().family());
        assertTrue(transfer.isReady(player, chest));
        assertEquals(1, transfer.expectedPlayerAfter(player).orElseThrow()
                .quantityOf(ProductionMaterials.COAL));
        assertEquals(8, transfer.expectedChestAfter(chest).orElseThrow()
                .quantityOf(ProductionMaterials.COBBLESTONE));
        assertFalse(transfer.isReady(ProductionLedger.empty(), chest));
    }

    @Test
    void preflightRejectsMissingChestEvidenceAndNonemptyCursor() {
        SingleChestTransfer transfer = new SingleChestTransfer(
                ProductionLedger.of(ProductionMaterials.COBBLESTONE, 8),
                ProductionLedger.of(ProductionMaterials.COAL, 1),
                12);
        ProductionPlanNode planNode = new ProductionPlanNode("store_stone",
                transfer, false);
        ProductionResolvedNode resolved = new ProductionResolvedNode(planNode,
                transfer.menuContract().expectedPlayerDelta(),
                Optional.of(transfer.menuContract()), Optional.empty());
        ProductionPreconditionValidator validator =
                new ProductionPreconditionValidator();
        ProductionLedger player = ProductionLedger.of(
                ProductionMaterials.COBBLESTONE, 8);

        ProductionPreconditionResult noChest = validator.validate(resolved,
                new ProductionPreconditionSnapshot(1, player,
                        Optional.of(MenuFamily.CHEST_3X9), true,
                        Optional.empty()));
        ProductionPreconditionResult cursor = validator.validate(resolved,
                new ProductionPreconditionSnapshot(2, player,
                        Optional.of(MenuFamily.CHEST_3X9), false,
                        Optional.of(ProductionLedger.of(
                                ProductionMaterials.COAL, 1))));

        assertFalse(noChest.ready());
        assertEquals(ProductionPreconditionRejection.CHEST_LEDGER_MISSING,
                noChest.rejection().orElseThrow());
        assertFalse(cursor.ready());
        assertEquals(ProductionPreconditionRejection.CURSOR_NOT_EMPTY,
                cursor.rejection().orElseThrow());
    }

    @Test
    void recipePreflightRequiresTheExactInventoryMenuFamilyAndDelta() {
        ProductionResolvedNode planks = ProductionPlanValidator.p5aDefault()
                .validate(WoodToIronPickTemplate.create())
                .resolvedNodes()
                .stream()
                .filter(node -> node.node().nodeId().equals("craft_planks"))
                .findFirst()
                .orElseThrow();
        ProductionPreconditionValidator validator =
                new ProductionPreconditionValidator();

        ProductionPreconditionResult ready = validator.validate(planks,
                new ProductionPreconditionSnapshot(3,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4),
                        Optional.of(MenuFamily.INVENTORY_2X2), true,
                        Optional.empty()));
        ProductionPreconditionResult wrongFamily = validator.validate(planks,
                new ProductionPreconditionSnapshot(4,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4),
                        Optional.of(MenuFamily.CRAFTING_3X3), true,
                        Optional.empty()));

        assertTrue(ready.ready());
        assertEquals(16, ready.expectedPlayerAfter().orElseThrow()
                .quantityOf(ProductionMaterials.OAK_PLANKS));
        assertFalse(wrongFamily.ready());
        assertEquals(ProductionPreconditionRejection.MENU_FAMILY_MISMATCH,
                wrongFamily.rejection().orElseThrow());
    }

    @Test
    void acquisitionDoesNotRunThroughALeftOpenMenu() {
        ProductionResolvedNode harvest = ProductionPlanValidator.p5aDefault()
                .validate(WoodToIronPickTemplate.create())
                .resolvedNodes()
                .stream()
                .filter(node -> node.node().nodeId().equals("harvest_logs"))
                .findFirst()
                .orElseThrow();

        ProductionPreconditionResult result =
                new ProductionPreconditionValidator().validate(harvest,
                        new ProductionPreconditionSnapshot(5,
                                ProductionLedger.empty(),
                                Optional.of(MenuFamily.INVENTORY_2X2), true,
                                Optional.empty()));

        assertFalse(result.ready());
        assertEquals(ProductionPreconditionRejection.UNEXPECTED_MENU_OPEN,
                result.rejection().orElseThrow());
    }
}
