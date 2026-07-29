package io.github.greytaiwolf.botplayer.navigation.path;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

public final class NavigationSnapshot {
    public static final int MAXIMUM_CELLS = 262_144;

    private final UUID snapshotId;
    private final UUID botId;
    private final long botGeneration;
    private final String dimension;
    private final long createdTick;
    private final GridPoint minimum;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final TraversalCell[] cells;
    private final boolean coverageComplete;
    private final long changeSequence;

    public NavigationSnapshot(
            UUID snapshotId,
            UUID botId,
            long botGeneration,
            String dimension,
            long createdTick,
            GridPoint minimum,
            int sizeX,
            int sizeY,
            int sizeZ,
            TraversalCell[] cells,
            boolean coverageComplete,
            long changeSequence) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.botId = Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        if (dimension == null || dimension.isBlank() || dimension.length() > 128) {
            throw new IllegalArgumentException(
                    "dimension must contain 1-128 visible characters");
        }
        if (createdTick < 0L || changeSequence < 0L) {
            throw new IllegalArgumentException(
                    "createdTick and changeSequence must not be negative");
        }
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.sizeX = requireSize(sizeX, "sizeX");
        this.sizeY = requireSize(sizeY, "sizeY");
        this.sizeZ = requireSize(sizeZ, "sizeZ");
        int expected = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        if (expected > MAXIMUM_CELLS) {
            throw new IllegalArgumentException(
                    "snapshot exceeds " + MAXIMUM_CELLS + " cells");
        }
        Objects.requireNonNull(cells, "cells");
        if (cells.length != expected) {
            throw new IllegalArgumentException(
                    "cells length does not match snapshot dimensions");
        }
        this.cells = cells.clone();
        for (TraversalCell cell : this.cells) {
            Objects.requireNonNull(cell, "cell");
        }
        this.botGeneration = botGeneration;
        this.dimension = dimension;
        this.createdTick = createdTick;
        this.coverageComplete = coverageComplete;
        this.changeSequence = changeSequence;
    }

    public UUID snapshotId() {
        return snapshotId;
    }

    public UUID botId() {
        return botId;
    }

    public long botGeneration() {
        return botGeneration;
    }

    public String dimension() {
        return dimension;
    }

    public long createdTick() {
        return createdTick;
    }

    public GridPoint minimum() {
        return minimum;
    }

    public int sizeX() {
        return sizeX;
    }

    public int sizeY() {
        return sizeY;
    }

    public int sizeZ() {
        return sizeZ;
    }

    public int cellCount() {
        return cells.length;
    }

    public boolean coverageComplete() {
        return coverageComplete;
    }

    public long changeSequence() {
        return changeSequence;
    }

    public boolean contains(GridPoint point) {
        int localX = point.x() - minimum.x();
        int localY = point.y() - minimum.y();
        int localZ = point.z() - minimum.z();
        return localX >= 0
                && localX < sizeX
                && localY >= 0
                && localY < sizeY
                && localZ >= 0
                && localZ < sizeZ;
    }

    public TraversalCell cell(GridPoint point) {
        return contains(point) ? cells[index(point)] : TraversalCell.UNKNOWN;
    }

    public int index(GridPoint point) {
        if (!contains(point)) {
            throw new IndexOutOfBoundsException("point is outside snapshot");
        }
        int localX = point.x() - minimum.x();
        int localY = point.y() - minimum.y();
        int localZ = point.z() - minimum.z();
        return (localY * sizeZ + localZ) * sizeX + localX;
    }

    public GridPoint point(int index) {
        if (index < 0 || index >= cells.length) {
            throw new IndexOutOfBoundsException("cell index is outside snapshot");
        }
        int localX = index % sizeX;
        int remainder = index / sizeX;
        int localZ = remainder % sizeZ;
        int localY = remainder / sizeZ;
        return new GridPoint(
                minimum.x() + localX,
                minimum.y() + localY,
                minimum.z() + localZ);
    }

    public TraversalCell[] copyCells() {
        return Arrays.copyOf(cells, cells.length);
    }

    private static int requireSize(int size, String name) {
        if (size < 1 || size > 256) {
            throw new IllegalArgumentException(name + " must be between 1 and 256");
        }
        return size;
    }
}
