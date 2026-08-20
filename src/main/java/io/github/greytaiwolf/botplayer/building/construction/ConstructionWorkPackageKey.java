package io.github.greytaiwolf.botplayer.building.construction;

import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintContentHash;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable identity of one work package for one exact blueprint revision and content.
 *
 * <p>The ordinal is an identity component, not a world position or authority to place blocks. A
 * later site-aware compiler must revalidate this complete key before it can reuse a package.
 */
public record ConstructionWorkPackageKey(
        UUID blueprintId,
        long blueprintRevision,
        BlueprintContentHash blueprintContentHash,
        int ordinal)
        implements Comparable<ConstructionWorkPackageKey> {
    /** At most 256 blueprint cells split into packages containing at least 16 cells. */
    public static final int MAX_WORK_PACKAGES = 16;

    public ConstructionWorkPackageKey {
        blueprintId = requireNonZero(blueprintId, "blueprintId");
        if (blueprintRevision < 1L) {
            throw new IllegalArgumentException("blueprintRevision must be positive");
        }
        Objects.requireNonNull(blueprintContentHash, "blueprintContentHash");
        if (ordinal < 0 || ordinal >= MAX_WORK_PACKAGES) {
            throw new IllegalArgumentException("work package ordinal is outside the bounded limit");
        }
    }

    /** Creates a key tied to all immutable blueprint identity and content fields. */
    public static ConstructionWorkPackageKey forBlueprint(Blueprint blueprint, int ordinal) {
        Blueprint required = Objects.requireNonNull(blueprint, "blueprint");
        return new ConstructionWorkPackageKey(required.blueprintId(), required.revision(),
                required.contentHash(), ordinal);
    }

    @Override
    public int compareTo(ConstructionWorkPackageKey other) {
        ConstructionWorkPackageKey required = Objects.requireNonNull(other, "other");
        int blueprintComparison = blueprintId.compareTo(required.blueprintId);
        if (blueprintComparison != 0) {
            return blueprintComparison;
        }
        int revisionComparison = Long.compare(blueprintRevision, required.blueprintRevision);
        if (revisionComparison != 0) {
            return revisionComparison;
        }
        int contentComparison = blueprintContentHash.compareTo(required.blueprintContentHash);
        if (contentComparison != 0) {
            return contentComparison;
        }
        return Integer.compare(ordinal, required.ordinal);
    }

    private static UUID requireNonZero(UUID value, String name) {
        UUID required = Objects.requireNonNull(value, name);
        if (required.getMostSignificantBits() == 0L
                && required.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException(name + " must not be the zero UUID");
        }
        return required;
    }
}
