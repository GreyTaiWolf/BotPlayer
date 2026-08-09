package io.github.greytaiwolf.botplayer.skill.builtin.production;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionPlanValidatorTest {
    @Test
    void unknownRecipeIsRejectedBeforeAnyExecutableNodeIsExposed() {
        ProductionPlanTemplate original = WoodToIronPickTemplate.create();
        List<ProductionPlanNode> nodes = new ArrayList<>(original.nodes());
        nodes.set(1, new ProductionPlanNode("craft_planks",
                new RecipeExecution(new ProductionRecipeId("unreviewed_recipe"), 1),
                false));

        ProductionPlanValidation validation = ProductionPlanValidator.p5aDefault()
                .validate(new ProductionPlanTemplate(original.schema(),
                        original.requestedBudget(), nodes, original.edges()));

        assertFalse(validation.accepted());
        assertTrue(validation.resolvedNodes().isEmpty());
        assertTrue(validation.violations().stream().anyMatch(violation ->
                violation.code() == ProductionPlanViolationCode.UNKNOWN_RECIPE));
    }

    @Test
    void wrongAcquisitionMethodAndBudgetAreBothFailClosed() {
        ProductionPlanTemplate original = WoodToIronPickTemplate.create();
        List<ProductionPlanNode> nodes = new ArrayList<>(original.nodes());
        nodes.set(0, new ProductionPlanNode("harvest_logs",
                new ResourceAcquisition(AcquisitionMethod.MINE_COAL,
                        ProductionLedger.of(ProductionMaterials.OAK_LOG, 4)),
                true));
        ProductionBudget tooSmall = new ProductionBudget(12, 17, 11, 122, 3);

        ProductionPlanValidation validation = ProductionPlanValidator.p5aDefault()
                .validate(new ProductionPlanTemplate(original.schema(), tooSmall,
                        nodes, original.edges()));

        assertFalse(validation.accepted());
        assertTrue(validation.violations().stream().anyMatch(violation ->
                violation.code()
                == ProductionPlanViolationCode.ACQUISITION_METHOD_MISMATCH));
        assertTrue(validation.violations().stream().anyMatch(violation ->
                violation.code() == ProductionPlanViolationCode.BUDGET_EXCEEDED));
    }

    @Test
    void cyclesAreRejectedWithoutTryingToProjectResources() {
        ProductionPlanTemplate original = WoodToIronPickTemplate.create();
        List<ProductionPlanEdge> edges = new ArrayList<>(original.edges());
        edges.add(new ProductionPlanEdge("iron_pickaxe", "harvest_logs"));

        ProductionPlanValidation validation = ProductionPlanValidator.p5aDefault()
                .validate(new ProductionPlanTemplate(original.schema(),
                        original.requestedBudget(), original.nodes(), edges));

        assertFalse(validation.accepted());
        assertTrue(validation.violations().stream().anyMatch(violation ->
                violation.code() == ProductionPlanViolationCode.CYCLIC_DAG));
    }
}
