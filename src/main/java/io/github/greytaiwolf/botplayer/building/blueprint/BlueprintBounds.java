package io.github.greytaiwolf.botplayer.building.blueprint;

import java.util.List;
import java.util.Objects;

/** Immutable inclusive bounds derived from the cells of one blueprint. */
public record BlueprintBounds(
        BlueprintOffset minimum, BlueprintOffset maximum) {
    public BlueprintBounds {
        Objects.requireNonNull(minimum, "minimum");
        Objects.requireNonNull(maximum, "maximum");
        if (minimum.x() > maximum.x()
                || minimum.y() > maximum.y()
                || minimum.z() > maximum.z()) {
            throw new IllegalArgumentException(
                    "blueprint bounds minimum must not exceed maximum");
        }
    }

    static BlueprintBounds fromCanonicalCells(List<BlueprintCell> cells) {
        Objects.requireNonNull(cells, "cells");
        if (cells.isEmpty()) {
            throw new IllegalArgumentException(
                    "blueprint bounds require at least one cell");
        }
        BlueprintOffset first = Objects.requireNonNull(cells.getFirst(),
                "blueprint cell").offset();
        int minimumX = first.x();
        int minimumY = first.y();
        int minimumZ = first.z();
        int maximumX = first.x();
        int maximumY = first.y();
        int maximumZ = first.z();
        for (BlueprintCell cell : cells) {
            BlueprintOffset offset = Objects.requireNonNull(cell,
                    "blueprint cell").offset();
            minimumX = Math.min(minimumX, offset.x());
            minimumY = Math.min(minimumY, offset.y());
            minimumZ = Math.min(minimumZ, offset.z());
            maximumX = Math.max(maximumX, offset.x());
            maximumY = Math.max(maximumY, offset.y());
            maximumZ = Math.max(maximumZ, offset.z());
        }
        return new BlueprintBounds(
                new BlueprintOffset(minimumX, minimumY, minimumZ),
                new BlueprintOffset(maximumX, maximumY, maximumZ));
    }

    public int width() {
        return inclusiveSpan(minimum.x(), maximum.x());
    }

    public int height() {
        return inclusiveSpan(minimum.y(), maximum.y());
    }

    public int depth() {
        return inclusiveSpan(minimum.z(), maximum.z());
    }

    public long volume() {
        long plane = Math.multiplyExact((long) width(), (long) height());
        return Math.multiplyExact(plane, (long) depth());
    }

    private static int inclusiveSpan(int minimum, int maximum) {
        return Math.toIntExact((long) maximum - minimum + 1L);
    }
}
