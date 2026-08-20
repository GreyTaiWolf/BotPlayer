package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Exact, immutable explicit item evidence for every distinct expected state in one blueprint.
 *
 * <p>Evidence is canonically ordered by the first occurrence of its target state in the blueprint's
 * already-canonical cell order. The contract intentionally does not infer a block-id-to-item-id mapping,
 * establish item availability, or authorize any later operation.
 */
public record BlueprintPlaceableItemEvidence(
        Blueprint blueprint,
        List<PlaceableItemEvidence> evidence) {
    public BlueprintPlaceableItemEvidence {
        blueprint = Objects.requireNonNull(blueprint, "blueprint");
        evidence = canonicalAndValidate(blueprint, evidence);
    }

    /**
     * Returns the explicitly declared item id for one exact blueprint target offset.
     *
     * @throws IllegalArgumentException if the offset is not a target in this exact blueprint
     */
    public ResourceId placeableItemFor(BlueprintOffset offset) {
        BlueprintOffset checkedOffset = Objects.requireNonNull(offset, "offset");
        for (BlueprintCell cell : blueprint.cells()) {
            if (cell.offset().equals(checkedOffset)) {
                return evidenceFor(cell.expectedState()).placeableItemId();
            }
        }
        throw new IllegalArgumentException(
                "blueprint offset is not an exact target for declared item evidence");
    }

    /**
     * Derives bounded canonical declared item quantities from exact blueprint cells and explicit evidence.
     *
     * <p>Every cell first resolves through its full target-state fingerprint. Quantities are then
     * aggregated only when the resulting explicit item id and material class both match.
     */
    public BlueprintPlaceableItemRequirements declaredItemRequirements() {
        Map<RequirementKey, Integer> quantities = new LinkedHashMap<>();
        for (BlueprintCell cell : blueprint.cells()) {
            PlaceableItemEvidence declaredEvidence = evidenceFor(cell.expectedState());
            RequirementKey key = new RequirementKey(declaredEvidence.placeableItemId(),
                    cell.materialClass());
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

    private PlaceableItemEvidence evidenceFor(BlockStateFingerprint targetState) {
        for (PlaceableItemEvidence entry : evidence) {
            if (entry.targetState().equals(targetState)) {
                return entry;
            }
        }
        throw new IllegalStateException(
                "exact blueprint evidence is missing a validated target state");
    }

    private static List<PlaceableItemEvidence> canonicalAndValidate(
            Blueprint blueprint, List<PlaceableItemEvidence> supplied) {
        Objects.requireNonNull(supplied, "evidence");

        Map<BlockStateFingerprint, Boolean> expectedStates = new LinkedHashMap<>();
        for (BlueprintCell cell : blueprint.cells()) {
            expectedStates.putIfAbsent(cell.expectedState(), Boolean.TRUE);
        }

        Map<BlockStateFingerprint, PlaceableItemEvidence> evidenceByState = new HashMap<>();
        int suppliedCount = 0;
        for (PlaceableItemEvidence entry : supplied) {
            PlaceableItemEvidence checkedEntry = Objects.requireNonNull(entry,
                    "placeable item evidence");
            suppliedCount = Math.addExact(suppliedCount, 1);
            if (suppliedCount > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "placeable item evidence exceeds the bounded blueprint limit");
            }
            if (!expectedStates.containsKey(checkedEntry.targetState())) {
                throw new IllegalArgumentException(
                        "placeable item evidence must exactly cover every distinct blueprint target state");
            }
            if (evidenceByState.putIfAbsent(checkedEntry.targetState(), checkedEntry) != null) {
                throw new IllegalArgumentException(
                        "placeable item evidence must not duplicate a target state");
            }
        }
        if (evidenceByState.size() != expectedStates.size()) {
            throw new IllegalArgumentException(
                    "placeable item evidence must exactly cover every distinct blueprint target state");
        }

        List<PlaceableItemEvidence> canonical = new ArrayList<>(expectedStates.size());
        for (BlockStateFingerprint expectedState : expectedStates.keySet()) {
            canonical.add(evidenceByState.get(expectedState));
        }
        return List.copyOf(canonical);
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
