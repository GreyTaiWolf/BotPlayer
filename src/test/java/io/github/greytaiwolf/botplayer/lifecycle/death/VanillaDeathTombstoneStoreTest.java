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
