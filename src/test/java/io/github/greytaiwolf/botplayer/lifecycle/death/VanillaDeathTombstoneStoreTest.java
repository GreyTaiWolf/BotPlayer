package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaDeathTombstoneStoreTest {
    private static final UUID BOT = new UUID(81L, 92L);
    private static final UUID TRANSACTION = new UUID(19L, 27L);

    @TempDir
    Path temporaryDirectory;

    @Test
    void roundTripsAndClearsOnlyTheExactTransaction()
            throws IOException {
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        temporaryDirectory.resolve("death-wal"));
        VanillaDeathTicket ticket = ticket();

        store.arm(ticket);
        store.arm(ticket);
        Assertions.assertEquals(
                ticket, store.read(BOT).orElseThrow());
        Assertions.assertThrows(
                IOException.class,
                () -> store.clear(BOT, UUID.randomUUID()));
        store.clear(BOT, TRANSACTION);
        Assertions.assertTrue(store.read(BOT).isEmpty());
        store.clear(BOT, TRANSACTION);
    }

    @Test
    void corruptedOrOversizedMarkersFailClosed()
            throws IOException {
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        temporaryDirectory.resolve("death-wal"));
        store.arm(ticket());
        Path marker = store.markerPath(BOT);
        byte[] corrupted = Files.readAllBytes(marker);
        corrupted[12] ^= 0x5A;
        Files.write(marker, corrupted);
        Assertions.assertThrows(
                IOException.class, () -> store.read(BOT));

        Files.write(marker, new byte[513]);
        Assertions.assertThrows(
                IOException.class, () -> store.read(BOT));
    }

    @Test
    void existenceLookupFailureIsNotTreatedAsAnAbsentMarker() {
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        temporaryDirectory.resolve("death-wal"),
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.EXISTS));

        Assertions.assertThrows(
                IOException.class, () -> store.read(BOT));
    }

    @Test
    void atomicMoveFailureDoesNotPublishAnArmedMarker()
            throws IOException {
        Path directory = temporaryDirectory.resolve("death-wal");
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        directory,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.ATOMIC_MOVE));

        Assertions.assertThrows(
                IOException.class, () -> store.arm(ticket()));
        Assertions.assertFalse(Files.exists(store.markerPath(BOT)));
        Assertions.assertFalse(Files.exists(
                directory.resolve(BOT + ".death-v2.tmp")));
    }

    @Test
    void finalMarkerForceFailureNeverReportsArmSuccess()
            throws IOException {
        Path directory = temporaryDirectory.resolve("death-wal");
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        directory,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.FORCE_FILE));

        Assertions.assertThrows(
                IOException.class, () -> store.arm(ticket()));
        Assertions.assertEquals(
                ticket(), new VanillaDeathTombstoneStore(directory)
                        .read(BOT).orElseThrow());
    }

    @Test
    void postPublishDirectoryForceFailureNeverReportsArmSuccess()
            throws IOException {
        Path directory = temporaryDirectory.resolve("death-wal");
        Files.createDirectories(directory);
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        directory,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.FORCE_DIRECTORY,
                                2));

        Assertions.assertThrows(
                IOException.class, () -> store.arm(ticket()));
        Assertions.assertEquals(
                ticket(), new VanillaDeathTombstoneStore(directory)
                        .read(BOT).orElseThrow());
    }

    @Test
    void readbackFailurePreventsClearFromDeletingTheMarker()
            throws IOException {
        Path directory = temporaryDirectory.resolve("death-wal");
        VanillaDeathTombstoneStore baseline =
                new VanillaDeathTombstoneStore(directory);
        baseline.arm(ticket());
        VanillaDeathTombstoneStore store =
                new VanillaDeathTombstoneStore(
                        directory,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.READ_BOUNDED));

        Assertions.assertThrows(
                IOException.class, () -> store.read(BOT));
        VanillaDeathTombstoneStore clearAttempt =
                new VanillaDeathTombstoneStore(
                        directory,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.READ_BOUNDED));
        Assertions.assertThrows(
                IOException.class,
                () -> clearAttempt.clear(BOT, TRANSACTION));
        Assertions.assertEquals(ticket(), baseline.read(BOT).orElseThrow());
    }

    private static VanillaDeathTicket ticket() {
        return VanillaDeathTicket.create(
                TRANSACTION,
                BOT,
                7L,
                41L,
                new DeathExperienceSnapshot(
                        12, 345, 0.375F),
                true,
                0);
    }
}
