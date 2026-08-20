package io.github.greytaiwolf.botplayer.building.blueprint;

import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Canonical structural bill of planned block requirements for a bounded blueprint.
 *
 * <p>This is deliberately not an inventory reservation and does not assert a {@code blockId -> itemId}
 * mapping. It only aggregates the desired block identifiers and their permanent/temporary class.
 */
public record BlueprintBlockRequirements(
        List<BlueprintBlockRequirement> entries) {
    public BlueprintBlockRequirements {
        Objects.requireNonNull(entries, "entries");
        if (entries.isEmpty()) {
            throw new IllegalArgumentException(
                    "blueprint block requirements must not be empty");
        }
        List<BlueprintBlockRequirement> canonical = new ArrayList<>(
                Math.min(entries.size(), Blueprint.MAX_CELLS));
        for (BlueprintBlockRequirement entry : entries) {
            canonical.add(Objects.requireNonNull(entry,
                    "blueprint block requirement"));
            if (canonical.size() > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "blueprint block requirements exceed the bounded blueprint limit");
            }
        }
        if (canonical.isEmpty()) {
            throw new IllegalArgumentException(
                    "blueprint block requirements must not be empty");
        }
        canonical.sort(Comparator
                .comparing((BlueprintBlockRequirement entry) ->
                        entry.blockId().value())
                .thenComparing(entry -> entry.materialClass().name()));
        int totalBlocks = 0;
        BlueprintBlockRequirement previous = null;
        for (BlueprintBlockRequirement entry : canonical) {
            if (previous != null
                    && previous.blockId().equals(entry.blockId())
                    && previous.materialClass() == entry.materialClass()) {
                throw new IllegalArgumentException(
                        "blueprint block requirements must aggregate duplicate keys");
            }
            totalBlocks = Math.addExact(totalBlocks, entry.count());
            if (totalBlocks > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "blueprint block requirements exceed the bounded blueprint limit");
            }
            previous = entry;
        }
        entries = List.copyOf(canonical);
    }

    static BlueprintBlockRequirements fromCells(List<BlueprintCell> cells) {
        Objects.requireNonNull(cells, "cells");
        Map<RequirementKey, Integer> quantities = new TreeMap<>();
        int observedCells = 0;
        for (BlueprintCell cell : cells) {
            BlueprintCell nonNullCell = Objects.requireNonNull(cell,
                    "blueprint cell");
            observedCells = Math.addExact(observedCells, 1);
            if (observedCells > Blueprint.MAX_CELLS) {
                throw new IllegalArgumentException(
                        "blueprint cells exceed the bounded blueprint limit");
            }
            RequirementKey key = new RequirementKey(
                    nonNullCell.expectedState().blockId(),
                    nonNullCell.materialClass());
            quantities.merge(key, 1, Math::addExact);
        }
        List<BlueprintBlockRequirement> entries = new ArrayList<>(quantities.size());
        for (Map.Entry<RequirementKey, Integer> entry : quantities.entrySet()) {
            entries.add(new BlueprintBlockRequirement(entry.getKey().blockId(),
                    entry.getKey().materialClass(), entry.getValue()));
        }
        return new BlueprintBlockRequirements(entries);
    }

    public int totalBlocks() {
        int total = 0;
        for (BlueprintBlockRequirement entry : entries) {
            total = Math.addExact(total, entry.count());
        }
        return total;
    }

    private record RequirementKey(
            ResourceId blockId,
            BlueprintMaterialClass materialClass)
            implements Comparable<RequirementKey> {
        private RequirementKey {
            Objects.requireNonNull(blockId, "blockId");
            Objects.requireNonNull(materialClass, "materialClass");
        }

        @Override
        public int compareTo(RequirementKey other) {
            Objects.requireNonNull(other, "other");
            int blockIdComparison = blockId.value().compareTo(
                    other.blockId.value());
            if (blockIdComparison != 0) {
                return blockIdComparison;
            }
            return materialClass.name().compareTo(other.materialClass.name());
        }
    }
}
