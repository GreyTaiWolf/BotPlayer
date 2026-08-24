package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One bounded, immutable observation of explicitly declared construction materials in a bot's own
 * native inventory.
 *
 * <p>This value binds a current bot generation, the complete A4 declaration, an observed server tick,
 * and a short-lived native-menu fence. {@link Status#UNAVAILABLE_MENU} and
 * {@link Status#UNAVAILABLE_REGISTRY} deliberately carry no zero-valued findings: unavailable evidence
 * is not proof that a material is absent. The observation neither reserves, moves, nor consumes an
 * item, and is not a placement or construction permit.
 */
public record ConstructionMaterialAvailability(
        UUID botId,
        long botGeneration,
        BlueprintPlaceableItemEvidence declaredEvidence,
        long observedTick,
        Status status,
        Optional<InventoryMenuFence> inventoryMenuFence,
        List<Finding> findings) {
    private static final Comparator<Finding> FINDING_ORDER = Comparator.comparing(
            (Finding finding) -> finding.placeableItemId().value());

    public ConstructionMaterialAvailability {
        botId = Objects.requireNonNull(botId, "botId");
        if (botGeneration < 1L) {
            throw new IllegalArgumentException("material availability bot generation must be positive");
        }
        declaredEvidence = Objects.requireNonNull(declaredEvidence, "declaredEvidence");
        if (observedTick < 0L) {
            throw new IllegalArgumentException("material availability observed tick must not be negative");
        }
        status = Objects.requireNonNull(status, "status");
        inventoryMenuFence = Objects.requireNonNull(inventoryMenuFence, "inventoryMenuFence");
        findings = Objects.requireNonNull(findings, "findings");

        if (status.hasUsableInventoryObservation()) {
            if (inventoryMenuFence.isEmpty()) {
                throw new IllegalArgumentException(
                        "usable material availability requires a native inventory menu fence");
            }
            findings = canonicalAndValidate(declaredEvidence, findings);
            boolean hasShortage = findings.stream().anyMatch(Finding::hasShortage);
            if (status == Status.AVAILABLE && hasShortage) {
                throw new IllegalArgumentException(
                        "available material observation must not contain a shortage");
            }
            if (status == Status.SHORTAGE && !hasShortage) {
                throw new IllegalArgumentException(
                        "shortage material observation must contain a shortage");
            }
        } else {
            if (inventoryMenuFence.isPresent() || !findings.isEmpty()) {
                throw new IllegalArgumentException(
                        "unavailable material observation must not forge inventory findings");
            }
            findings = List.of();
        }
    }

    /** Creates an unavailable result when the explicit A4-R1 registry/default-state preflight fails. */
    public static ConstructionMaterialAvailability unavailableRegistry(
            UUID botId,
            long botGeneration,
            BlueprintPlaceableItemEvidence declaredEvidence,
            long observedTick) {
        return new ConstructionMaterialAvailability(botId, botGeneration, declaredEvidence,
                observedTick, Status.UNAVAILABLE_REGISTRY, Optional.empty(), List.of());
    }

    /** Creates an unavailable result when the active native inventory menu cannot be safely observed. */
    public static ConstructionMaterialAvailability unavailableMenu(
            UUID botId,
            long botGeneration,
            BlueprintPlaceableItemEvidence declaredEvidence,
            long observedTick) {
        return new ConstructionMaterialAvailability(botId, botGeneration, declaredEvidence,
                observedTick, Status.UNAVAILABLE_MENU, Optional.empty(), List.of());
    }

    /** True only when this point-in-time observation found every aggregated explicit item quantity. */
    public boolean isAvailable() {
        return status == Status.AVAILABLE;
    }

    private static List<Finding> canonicalAndValidate(
            BlueprintPlaceableItemEvidence declaredEvidence, List<Finding> supplied) {
        List<Finding> canonical = new ArrayList<>(Math.min(supplied.size(), Blueprint.MAX_CELLS));
        for (Finding finding : supplied) {
            canonical.add(Objects.requireNonNull(finding, "material availability finding"));
            if (canonical.size() > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "material availability findings exceed the bounded blueprint limit");
            }
        }
        canonical.sort(FINDING_ORDER);

        Map<ResourceId, Integer> expectedRequired = new HashMap<>();
        for (BlueprintPlaceableItemRequirement requirement : declaredEvidence
                .declaredItemRequirements().entries()) {
            expectedRequired.merge(requirement.placeableItemId(), requirement.count(), Math::addExact);
        }
        if (canonical.size() != expectedRequired.size()) {
            throw new IllegalArgumentException(
                    "material availability findings must exactly cover aggregated declared item ids");
        }
        for (Finding finding : canonical) {
            Integer requiredCount = expectedRequired.remove(finding.placeableItemId());
            if (requiredCount == null || requiredCount != finding.requiredCount()) {
                throw new IllegalArgumentException(
                        "material availability finding must match an aggregated declared item quantity");
            }
        }
        if (!expectedRequired.isEmpty()) {
            throw new IllegalArgumentException(
                    "material availability findings must exactly cover aggregated declared item ids");
        }
        return List.copyOf(canonical);
    }

    /** The only outcomes of this read-only inventory observation. */
    public enum Status {
        /** Every explicit item requirement is currently observed in the accepted player inventory slots. */
        AVAILABLE,
        /** The inventory was safely observed, but one or more explicit item quantities are short. */
        SHORTAGE,
        /** The current native inventory menu or cursor was not safe to observe. */
        UNAVAILABLE_MENU,
        /** The explicit A4 declaration did not pass the current native registry/default-state preflight. */
        UNAVAILABLE_REGISTRY;

        boolean hasUsableInventoryObservation() {
            return this == AVAILABLE || this == SHORTAGE;
        }
    }

    /**
     * Minimal native menu fence captured with a usable observation.
     *
     * <p>It is not a live menu handle or a future transaction authorization. A later side-effecting
     * path must capture and revalidate its own exact native-menu state.
     */
    public record InventoryMenuFence(
            int containerId,
            int stateId,
            int selectedHotbar) {
        public InventoryMenuFence {
            if (containerId < 0) {
                throw new IllegalArgumentException("material availability container id must not be negative");
            }
            if (stateId < 0) {
                throw new IllegalArgumentException("material availability state id must not be negative");
            }
            if (selectedHotbar < 0 || selectedHotbar > 8) {
                throw new IllegalArgumentException(
                        "material availability selected hotbar slot must be in 0..8");
            }
        }
    }

    /** One canonical item-level quantity after all permanent and temporary declarations share an item id. */
    public record Finding(
            ResourceId placeableItemId,
            int requiredCount,
            int availableCount,
            int shortageCount) {
        public Finding {
            placeableItemId = Objects.requireNonNull(placeableItemId, "placeableItemId");
            if (requiredCount < 1 || requiredCount > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "material availability required count is outside the bounded blueprint limit");
            }
            if (availableCount < 0) {
                throw new IllegalArgumentException("material availability available count must not be negative");
            }
            int expectedShortage = Math.max(0, requiredCount - availableCount);
            if (shortageCount != expectedShortage) {
                throw new IllegalArgumentException(
                        "material availability shortage must exactly equal required minus available");
            }
        }

        /** Returns true when this item-level observation is smaller than its aggregate declaration. */
        public boolean hasShortage() {
            return shortageCount > 0;
        }
    }
}
