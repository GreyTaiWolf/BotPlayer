package io.github.greytaiwolf.botplayer.navigation.snapshot;

import io.github.greytaiwolf.botplayer.navigation.path.NavigationSnapshot;
import java.util.Objects;
import java.util.Optional;

public record SnapshotBuildProgress(
        Status status,
        int sampledThisTick,
        int sampledTotal,
        Optional<NavigationSnapshot> snapshot,
        String safeSummary) {
    public SnapshotBuildProgress {
        Objects.requireNonNull(status, "status");
        if (sampledThisTick < 0 || sampledTotal < 0) {
            throw new IllegalArgumentException(
                    "sample counts must not be negative");
        }
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(safeSummary, "safeSummary");
        if ((status == Status.COMPLETE) != snapshot.isPresent()) {
            throw new IllegalArgumentException(
                    "only COMPLETE progress contains a snapshot");
        }
    }

    public enum Status {
        BUILDING,
        COMPLETE,
        CANCELLED,
        STALE,
        TIMED_OUT
    }
}
