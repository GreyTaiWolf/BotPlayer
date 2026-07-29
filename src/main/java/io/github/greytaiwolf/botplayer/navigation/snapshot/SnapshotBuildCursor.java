package io.github.greytaiwolf.botplayer.navigation.snapshot;

import io.github.greytaiwolf.botplayer.navigation.GridPoint;
import io.github.greytaiwolf.botplayer.navigation.path.NavigationSnapshot;
import io.github.greytaiwolf.botplayer.navigation.path.TraversalCell;
import java.util.Objects;
import java.util.UUID;

/**
 * 只保存不可变身份、整数边界和已采样 DTO；不跨 Tick 持有 Level、BlockState 或实体。
 */
public final class SnapshotBuildCursor {
    private final UUID snapshotId;
    private final UUID botId;
    private final long botGeneration;
    private final String dimension;
    private final long startedTick;
    private final GridPoint minimum;
    private final int sizeX;
    private final int sizeY;
    private final int sizeZ;
    private final TraversalCell[] cells;
    private int nextIndex;
    private boolean coverageComplete = true;
    private boolean cancelled;

    SnapshotBuildCursor(
            UUID snapshotId,
            UUID botId,
            long botGeneration,
            String dimension,
            long startedTick,
            GridPoint minimum,
            int sizeX,
            int sizeY,
            int sizeZ) {
        this.snapshotId = Objects.requireNonNull(snapshotId, "snapshotId");
        this.botId = Objects.requireNonNull(botId, "botId");
        if (botGeneration <= 0L) {
            throw new IllegalArgumentException("botGeneration must be positive");
        }
        this.botGeneration = botGeneration;
        this.dimension = Objects.requireNonNull(dimension, "dimension");
        if (startedTick < 0L) {
            throw new IllegalArgumentException("startedTick must not be negative");
        }
        this.startedTick = startedTick;
        this.minimum = Objects.requireNonNull(minimum, "minimum");
        this.sizeX = sizeX;
        this.sizeY = sizeY;
        this.sizeZ = sizeZ;
        int count = Math.multiplyExact(Math.multiplyExact(sizeX, sizeY), sizeZ);
        if (count > NavigationSnapshot.MAXIMUM_CELLS) {
            throw new IllegalArgumentException("snapshot cell bound exceeded");
        }
        this.cells = new TraversalCell[count];
        java.util.Arrays.fill(this.cells, TraversalCell.UNKNOWN);
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

    public long startedTick() {
        return startedTick;
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

    public int sampledCells() {
        return nextIndex;
    }

    public boolean isComplete() {
        return nextIndex >= cells.length;
    }

    public boolean coverageComplete() {
        return coverageComplete;
    }

    public boolean isCancelled() {
        return cancelled;
    }

    public void cancel() {
        cancelled = true;
    }

    GridPoint nextPoint() {
        if (isComplete()) {
            throw new IllegalStateException("snapshot cursor is complete");
        }
        int localX = nextIndex % sizeX;
        int remainder = nextIndex / sizeX;
        int localZ = remainder % sizeZ;
        int localY = remainder / sizeZ;
        return new GridPoint(
                minimum.x() + localX,
                minimum.y() + localY,
                minimum.z() + localZ);
    }

    void accept(TraversalCell cell) {
        if (isComplete()) {
            throw new IllegalStateException("snapshot cursor is complete");
        }
        TraversalCell accepted = Objects.requireNonNull(cell, "cell");
        cells[nextIndex++] = accepted;
        coverageComplete &= accepted.known();
    }

    NavigationSnapshot finish(long finishedTick, long changeSequence) {
        if (!isComplete() || cancelled) {
            throw new IllegalStateException(
                    "only a complete active cursor can produce a snapshot");
        }
        return new NavigationSnapshot(
                snapshotId,
                botId,
                botGeneration,
                dimension,
                finishedTick,
                minimum,
                sizeX,
                sizeY,
                sizeZ,
                cells,
                coverageComplete,
                changeSequence);
    }
}
