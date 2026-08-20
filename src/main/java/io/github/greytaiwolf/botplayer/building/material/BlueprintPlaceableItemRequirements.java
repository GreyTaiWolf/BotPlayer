package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, canonical declared item quantities for a blueprint.
 *
 * <p>Entries are keyed by the explicit placeable item id and material class. The enclosing blueprint
 * evidence resolves every full target-state fingerprint before this aggregation occurs. This summary
 * deliberately carries no blueprint/work-package/site identity and is not a replay, reservation, or
 * placement authorization proof.
 */
public record BlueprintPlaceableItemRequirements(
        List<BlueprintPlaceableItemRequirement> entries) {
    private static final Comparator<BlueprintPlaceableItemRequirement> REQUIREMENT_ORDER =
            Comparator.comparing((BlueprintPlaceableItemRequirement requirement) ->
                    requirement.placeableItemId().value())
                    .thenComparing(BlueprintPlaceableItemRequirement::materialClass);

    public BlueprintPlaceableItemRequirements {
        Objects.requireNonNull(entries, "entries");
        List<BlueprintPlaceableItemRequirement> canonical = new ArrayList<>(
                Math.min(entries.size(), Blueprint.MAX_CELLS));
        for (BlueprintPlaceableItemRequirement entry : entries) {
            canonical.add(Objects.requireNonNull(entry, "blueprint placeable item requirement"));
            if (canonical.size() > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "blueprint placeable item requirements exceed the bounded blueprint limit");
            }
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "blueprint placeable item requirements must not be empty");
        }

        canonical.sort(REQUIREMENT_ORDER);
        int totalItems = 0;
        BlueprintPlaceableItemRequirement previous = null;
        for (BlueprintPlaceableItemRequirement entry : canonical) {
            if (previous != null
                    && previous.placeableItemId().equals(entry.placeableItemId())
                    && previous.materialClass() == entry.materialClass()) {
                throw new IllegalArgumentException(
                        "blueprint placeable item requirements must aggregate duplicate item and material class");
            }
            totalItems = Math.addExact(totalItems, entry.count());
            if (totalItems > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "blueprint placeable item requirements exceed the bounded blueprint limit");
            }
            previous = entry;
        }
        entries = List.copyOf(canonical);
    }

    /** Returns the exact bounded sum of all declared item quantities. */
    public int totalDeclaredItems() {
        int total = 0;
        for (BlueprintPlaceableItemRequirement entry : entries) {
            total = Math.addExact(total, entry.count());
        }
        return total;
    }

}
