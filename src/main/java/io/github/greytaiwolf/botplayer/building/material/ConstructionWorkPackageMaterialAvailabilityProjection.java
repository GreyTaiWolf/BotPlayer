package io.github.greytaiwolf.botplayer.building.material;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable, read-only comparison of one A8 work-package demand with one A7 inventory observation.
 *
 * <p>The projection aggregates the package's permanent and temporary requirements only for the
 * item-level comparison that A7 can make. It neither allocates the observed count between classes
 * nor between packages, reserves a slot or item, revalidates the native menu, reads a world or
 * container, or authorizes placement, an Action, Technique, or Skill.
 */
public final class ConstructionWorkPackageMaterialAvailabilityProjection {
    private static final Comparator<Finding> FINDING_ORDER = Comparator.comparing(
            (Finding finding) -> finding.placeableItemId().value());

    private final ConstructionWorkPackageMaterialDemand demand;
    private final ConstructionMaterialAvailability sourceAvailability;
    private final Status status;
    private final List<Finding> findings;

    private ConstructionWorkPackageMaterialAvailabilityProjection(
            ConstructionWorkPackageMaterialDemand demand,
            ConstructionMaterialAvailability sourceAvailability,
            Status status,
            List<Finding> findings) {
        this.demand = Objects.requireNonNull(demand, "demand");
        this.sourceAvailability = Objects.requireNonNull(sourceAvailability,
                "sourceAvailability");
        this.status = Objects.requireNonNull(status, "status");
        if (!demand.declaredEvidence().equals(sourceAvailability.declaredEvidence())) {
            throw new IllegalArgumentException(
                    "work package demand and material observation must bind exact evidence");
        }

        List<Finding> canonicalFindings = canonicalAndValidate(findings);
        switch (sourceAvailability.status()) {
            case UNAVAILABLE_MENU -> {
                requireUnavailable(status, Status.UNAVAILABLE_MENU, canonicalFindings);
            }
            case UNAVAILABLE_REGISTRY -> {
                requireUnavailable(status, Status.UNAVAILABLE_REGISTRY, canonicalFindings);
            }
            case AVAILABLE, SHORTAGE -> validateUsableObservation(status, canonicalFindings);
        }
        this.findings = canonicalFindings;
    }

    /**
     * Projects one exact package against one exact full-blueprint inventory observation.
     *
     * <p>An item count can make this one package look sufficient in isolation even when the full
     * blueprint is short, or when another package projects as sufficient too. Neither result is an
     * allocation, reservation, readiness proof, or permission to execute either package.
     */
    public static ConstructionWorkPackageMaterialAvailabilityProjection project(
            ConstructionWorkPackageMaterialDemand demand,
            ConstructionMaterialAvailability sourceAvailability) {
        ConstructionWorkPackageMaterialDemand checkedDemand = Objects.requireNonNull(demand,
                "demand");
        ConstructionMaterialAvailability checkedAvailability = Objects.requireNonNull(
                sourceAvailability, "sourceAvailability");
        if (!checkedDemand.declaredEvidence().equals(checkedAvailability.declaredEvidence())) {
            throw new IllegalArgumentException(
                    "work package demand and material observation must bind exact evidence");
        }

        return switch (checkedAvailability.status()) {
            case UNAVAILABLE_MENU -> new ConstructionWorkPackageMaterialAvailabilityProjection(
                    checkedDemand, checkedAvailability, Status.UNAVAILABLE_MENU, List.of());
            case UNAVAILABLE_REGISTRY -> new ConstructionWorkPackageMaterialAvailabilityProjection(
                    checkedDemand, checkedAvailability, Status.UNAVAILABLE_REGISTRY, List.of());
            case AVAILABLE, SHORTAGE -> projectUsableObservation(checkedDemand,
                    checkedAvailability);
        };
    }

    /** Returns the immutable A8 package demand that was compared. */
    public ConstructionWorkPackageMaterialDemand demand() {
        return demand;
    }

    /** Returns the immutable A7 source observation without extending its native-menu fence. */
    public ConstructionMaterialAvailability sourceAvailability() {
        return sourceAvailability;
    }

    /** Returns the only possible read-only projection outcome. */
    public Status status() {
        return status;
    }

    /**
     * Returns canonical item-level comparison findings, or no findings when the source was unavailable.
     */
    public List<Finding> findings() {
        return findings;
    }

    /** True only when every package item total was observed in this one accepted A7 snapshot. */
    public boolean observedItemTotalsSufficient() {
        return status == Status.OBSERVED_ITEM_TOTALS_SUFFICIENT;
    }

    private static ConstructionWorkPackageMaterialAvailabilityProjection projectUsableObservation(
            ConstructionWorkPackageMaterialDemand demand,
            ConstructionMaterialAvailability sourceAvailability) {
        Map<ResourceId, Integer> packageRequiredByItem = aggregatePackageRequirements(demand);
        Map<ResourceId, ConstructionMaterialAvailability.Finding> sourceFindings =
                indexSourceFindings(sourceAvailability.findings());
        List<Finding> findings = new ArrayList<>(packageRequiredByItem.size());
        boolean hasShortage = false;
        for (Map.Entry<ResourceId, Integer> entry : packageRequiredByItem.entrySet()) {
            ConstructionMaterialAvailability.Finding sourceFinding = sourceFindings.get(entry.getKey());
            if (sourceFinding == null) {
                throw new IllegalArgumentException(
                        "usable material observation must cover every package item id");
            }
            Finding finding = new Finding(entry.getKey(), entry.getValue(),
                    sourceFinding.availableCount(), Math.max(0,
                            entry.getValue() - sourceFinding.availableCount()));
            findings.add(finding);
            hasShortage |= finding.hasPackageShortage();
        }
        return new ConstructionWorkPackageMaterialAvailabilityProjection(demand, sourceAvailability,
                hasShortage ? Status.OBSERVED_ITEM_TOTALS_SHORTAGE
                        : Status.OBSERVED_ITEM_TOTALS_SUFFICIENT,
                findings);
    }

    private static Map<ResourceId, Integer> aggregatePackageRequirements(
            ConstructionWorkPackageMaterialDemand demand) {
        Map<ResourceId, Integer> quantities = new LinkedHashMap<>();
        for (BlueprintPlaceableItemRequirement requirement : demand.declaredRequirements().entries()) {
            quantities.merge(requirement.placeableItemId(), requirement.count(), Math::addExact);
        }
        return Map.copyOf(quantities);
    }

    private static Map<ResourceId, ConstructionMaterialAvailability.Finding> indexSourceFindings(
            List<ConstructionMaterialAvailability.Finding> sourceFindings) {
        Map<ResourceId, ConstructionMaterialAvailability.Finding> byItem = new LinkedHashMap<>();
        for (ConstructionMaterialAvailability.Finding sourceFinding : sourceFindings) {
            ConstructionMaterialAvailability.Finding prior = byItem.putIfAbsent(
                    sourceFinding.placeableItemId(), sourceFinding);
            if (prior != null) {
                throw new IllegalArgumentException(
                        "usable material observation must not duplicate an item finding");
            }
        }
        return Map.copyOf(byItem);
    }

    private static List<Finding> canonicalAndValidate(List<Finding> supplied) {
        Objects.requireNonNull(supplied, "findings");
        List<Finding> canonical = new ArrayList<>(Math.min(supplied.size(), Blueprint.MAX_CELLS));
        for (Finding finding : supplied) {
            canonical.add(Objects.requireNonNull(finding, "work package material finding"));
            if (canonical.size() > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "work package material projection findings exceed the bounded blueprint limit");
            }
        }
        canonical.sort(FINDING_ORDER);
        Finding previous = null;
        for (Finding finding : canonical) {
            if (previous != null && previous.placeableItemId().equals(finding.placeableItemId())) {
                throw new IllegalArgumentException(
                        "work package material projection findings must aggregate item ids");
            }
            previous = finding;
        }
        return List.copyOf(canonical);
    }

    private void requireUnavailable(
            Status actualStatus, Status expectedStatus, List<Finding> canonicalFindings) {
        if (actualStatus != expectedStatus || !canonicalFindings.isEmpty()) {
            throw new IllegalArgumentException(
                    "unavailable material observation must remain unavailable without findings");
        }
    }

    private void validateUsableObservation(Status actualStatus, List<Finding> canonicalFindings) {
        if (actualStatus != Status.OBSERVED_ITEM_TOTALS_SUFFICIENT
                && actualStatus != Status.OBSERVED_ITEM_TOTALS_SHORTAGE) {
            throw new IllegalArgumentException(
                    "usable material observation requires a usable projection status");
        }

        Map<ResourceId, Integer> expectedRequirements = new LinkedHashMap<>(
                aggregatePackageRequirements(demand));
        Map<ResourceId, ConstructionMaterialAvailability.Finding> sourceFindings =
                indexSourceFindings(sourceAvailability.findings());
        boolean hasShortage = false;
        for (Finding finding : canonicalFindings) {
            Integer expectedRequiredCount = expectedRequirements.remove(finding.placeableItemId());
            ConstructionMaterialAvailability.Finding sourceFinding = sourceFindings.get(
                    finding.placeableItemId());
            if (expectedRequiredCount == null
                    || expectedRequiredCount != finding.packageRequiredCount()
                    || sourceFinding == null
                    || sourceFinding.availableCount() != finding.observedAvailableCount()) {
                throw new IllegalArgumentException(
                        "projection findings must exactly match the demand and source observation");
            }
            hasShortage |= finding.hasPackageShortage();
        }
        if (!expectedRequirements.isEmpty()) {
            throw new IllegalArgumentException(
                    "projection findings must exactly cover the package item requirements");
        }
        Status expectedStatus = hasShortage ? Status.OBSERVED_ITEM_TOTALS_SHORTAGE
                : Status.OBSERVED_ITEM_TOTALS_SUFFICIENT;
        if (actualStatus != expectedStatus) {
            throw new IllegalArgumentException(
                    "projection status must exactly match its package item shortages");
        }
    }

    /** The only outcomes of an A8-to-A7 read-only item-total comparison. */
    public enum Status {
        /** Every item total for this one package was present in the one source observation. */
        OBSERVED_ITEM_TOTALS_SUFFICIENT,
        /** At least one item total for this one package was smaller than its source observation. */
        OBSERVED_ITEM_TOTALS_SHORTAGE,
        /** The source native menu was not safely observable and therefore has no findings. */
        UNAVAILABLE_MENU,
        /** The source registry/default-state preflight failed and therefore has no findings. */
        UNAVAILABLE_REGISTRY
    }

    /**
     * One item-level package comparison; it has no permanent/temporary allocation semantics.
     */
    public record Finding(
            ResourceId placeableItemId,
            int packageRequiredCount,
            int observedAvailableCount,
            int packageShortageCount) {
        public Finding {
            placeableItemId = Objects.requireNonNull(placeableItemId, "placeableItemId");
            if (packageRequiredCount < 1 || packageRequiredCount > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "work package material requirement is outside the bounded blueprint limit");
            }
            if (observedAvailableCount < 0) {
                throw new IllegalArgumentException(
                        "observed material availability must not be negative");
            }
            int expectedShortage = Math.max(0, packageRequiredCount - observedAvailableCount);
            if (packageShortageCount != expectedShortage) {
                throw new IllegalArgumentException(
                        "package material shortage must exactly equal required minus observed");
            }
        }

        /** Returns true when the source observation was short for this package item total. */
        public boolean hasPackageShortage() {
            return packageShortageCount > 0;
        }
    }
}
