package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackage;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackageKey;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, exact explicit material demand for one work package in one complete work plan.
 *
 * <p>This value derives its entries only from the package's exact cells and the A4 full-state
 * declaration. It keeps permanent and temporary classes distinct and does not inspect an A7
 * availability observation, allocate a stack, acquire a reservation, read a world or container,
 * or authorize a Technique, Skill, Action, or placement.
 */
public record ConstructionWorkPackageMaterialDemand(
        ConstructionWorkPlan workPlan,
        ConstructionWorkPackageKey workPackageKey,
        BlueprintPlaceableItemEvidence declaredEvidence,
        BlueprintPlaceableItemRequirements declaredRequirements) {
    public ConstructionWorkPackageMaterialDemand {
        workPlan = Objects.requireNonNull(workPlan, "workPlan");
        workPackageKey = Objects.requireNonNull(workPackageKey, "workPackageKey");
        declaredEvidence = Objects.requireNonNull(declaredEvidence, "declaredEvidence");
        declaredRequirements = Objects.requireNonNull(
                declaredRequirements, "declaredRequirements");
        if (!declaredEvidence.blueprint().equals(workPlan.blueprint())) {
            throw new IllegalArgumentException(
                    "work package material evidence must bind the exact plan blueprint");
        }

        ConstructionWorkPackage workPackage = requireWorkPackage(workPlan, workPackageKey);
        BlueprintPlaceableItemRequirements expectedRequirements = deriveRequirements(
                workPackage, declaredEvidence);
        if (!expectedRequirements.equals(declaredRequirements)) {
            throw new IllegalArgumentException(
                    "work package material requirements must exactly match the declared package cells");
        }
        if (declaredRequirements.totalDeclaredItems() != workPackage.cells().size()) {
            throw new IllegalArgumentException(
                    "work package material requirement total must equal the exact package cell count");
        }
    }

    /**
     * Derives the only accepted material demand for one exact package in a complete immutable plan.
     */
    public static ConstructionWorkPackageMaterialDemand derive(
            ConstructionWorkPlan workPlan,
            ConstructionWorkPackageKey workPackageKey,
            BlueprintPlaceableItemEvidence declaredEvidence) {
        ConstructionWorkPlan checkedPlan = Objects.requireNonNull(workPlan, "workPlan");
        ConstructionWorkPackageKey checkedKey = Objects.requireNonNull(
                workPackageKey, "workPackageKey");
        BlueprintPlaceableItemEvidence checkedEvidence = Objects.requireNonNull(
                declaredEvidence, "declaredEvidence");
        if (!checkedEvidence.blueprint().equals(checkedPlan.blueprint())) {
            throw new IllegalArgumentException(
                    "work package material evidence must bind the exact plan blueprint");
        }
        ConstructionWorkPackage workPackage = requireWorkPackage(checkedPlan, checkedKey);
        return new ConstructionWorkPackageMaterialDemand(checkedPlan, checkedKey, checkedEvidence,
                deriveRequirements(workPackage, checkedEvidence));
    }

    /** Returns the bounded exact total from this package's explicit `(itemId, materialClass)` entries. */
    public int totalDeclaredItems() {
        return declaredRequirements.totalDeclaredItems();
    }

    private static ConstructionWorkPackage requireWorkPackage(
            ConstructionWorkPlan workPlan, ConstructionWorkPackageKey workPackageKey) {
        return workPlan.workPackages().stream()
                .filter(workPackage -> workPackage.key().equals(workPackageKey))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "work package key must belong to the exact work plan"));
    }

    private static BlueprintPlaceableItemRequirements deriveRequirements(
            ConstructionWorkPackage workPackage,
            BlueprintPlaceableItemEvidence declaredEvidence) {
        Map<RequirementKey, Integer> quantities = new LinkedHashMap<>();
        for (BlueprintCell cell : workPackage.cells()) {
            ResourceId itemId = declaredEvidence.placeableItemFor(cell.offset());
            RequirementKey key = new RequirementKey(itemId, cell.materialClass());
            quantities.merge(key, 1, Math::addExact);
        }

        List<BlueprintPlaceableItemRequirement> requirements = new ArrayList<>(quantities.size());
        for (Map.Entry<RequirementKey, Integer> entry : quantities.entrySet()) {
            RequirementKey key = entry.getKey();
            requirements.add(new BlueprintPlaceableItemRequirement(key.placeableItemId(),
                    key.materialClass(), entry.getValue()));
        }
        return new BlueprintPlaceableItemRequirements(requirements);
    }

    private record RequirementKey(
            ResourceId placeableItemId,
            BlueprintMaterialClass materialClass) {
        private RequirementKey {
            placeableItemId = Objects.requireNonNull(placeableItemId, "placeableItemId");
            materialClass = Objects.requireNonNull(materialClass, "materialClass");
        }
    }
}
