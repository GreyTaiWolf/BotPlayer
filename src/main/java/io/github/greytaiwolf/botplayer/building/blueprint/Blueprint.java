package io.github.greytaiwolf.botplayer.building.blueprint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A schema-v1, bounded, immutable blueprint data contract.
 *
 * <p>This is deliberately upstream of site choice, material reservation, work packages, candidate
 * solving, Technique routing, Action ingress, and Minecraft world mutation. Its content hash is a
 * stable digest of the schema plus canonical cells only: identity and revision are intentionally
 * excluded so equal content can be deduplicated across revisions.
 */
public record Blueprint(
        UUID blueprintId,
        int schemaVersion,
        long revision,
        List<BlueprintCell> cells) {
    public static final int CURRENT_SCHEMA_VERSION = 1;
    public static final int MAX_CELLS = 256;
    public static final int MAX_AXIS_SPAN = 32;

    private static final Comparator<BlueprintCell> CELL_ORDER =
            Comparator.comparing(BlueprintCell::offset);

    public Blueprint {
        Objects.requireNonNull(blueprintId, "blueprintId");
        if (blueprintId.getMostSignificantBits() == 0L
                && blueprintId.getLeastSignificantBits() == 0L) {
            throw new IllegalArgumentException("blueprintId must not be the zero UUID");
        }
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("unsupported blueprint schema version");
        }
        if (revision < 1L) {
            throw new IllegalArgumentException("blueprint revision must be positive");
        }
        Objects.requireNonNull(cells, "cells");
        if (cells.isEmpty()) {
            throw new IllegalArgumentException(
                    "blueprint cells must be non-empty and within the bounded limit");
        }

        List<BlueprintCell> canonical = new ArrayList<>(
                Math.min(cells.size(), MAX_CELLS));
        for (BlueprintCell cell : cells) {
            canonical.add(Objects.requireNonNull(cell, "blueprint cell"));
            if (canonical.size() > MAX_CELLS) {
                throw new IllegalArgumentException(
                        "blueprint cells must be within the bounded limit");
            }
        }
        canonical.sort(CELL_ORDER);
        BlueprintCell previous = null;
        for (BlueprintCell cell : canonical) {
            if (previous != null && previous.offset().equals(cell.offset())) {
                throw new IllegalArgumentException(
                        "blueprint cells must not share an offset");
            }
            previous = cell;
        }
        BlueprintBounds bounds = BlueprintBounds.fromCanonicalCells(canonical);
        if (bounds.width() > MAX_AXIS_SPAN
                || bounds.height() > MAX_AXIS_SPAN
                || bounds.depth() > MAX_AXIS_SPAN) {
            throw new IllegalArgumentException(
                    "blueprint bounds exceed the bounded axis span");
        }
        cells = List.copyOf(canonical);
    }

    /** Inclusive bounds derived from canonical cells; no caller-supplied bounds are trusted. */
    public BlueprintBounds bounds() {
        return BlueprintBounds.fromCanonicalCells(cells);
    }

    /**
     * Structural planned-block requirements derived from cells.
     *
     * <p>These are not inventory reservations and do not map block IDs to item IDs.
     */
    public BlueprintBlockRequirements plannedBlockRequirements() {
        return BlueprintBlockRequirements.fromCells(cells);
    }

    /**
     * Deterministic content identity for this schema and canonical cells only.
     *
     * <p>The UUID, revision, derived bounds, and derived requirement list are intentionally excluded.
     */
    public BlueprintContentHash contentHash() {
        return BlueprintContentHash.fromCanonicalCells(schemaVersion, cells);
    }
}
