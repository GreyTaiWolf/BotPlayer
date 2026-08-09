package io.github.greytaiwolf.botplayer.lifecycle.death;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaDeathPlayerDataCommitterTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void injectedPlayerdataReadbackFailureFailsClosed()
            throws IOException {
        Path playerData = temporaryDirectory.resolve("bot.dat");
        Files.write(playerData, new byte[] {1});
        DurableFileOps fileOps = new FaultInjectingDurableFileOps(
                FaultInjectingDurableFileOps.Point.READ_BOUNDED);

        IOException exception = Assertions.assertThrows(
                IOException.class,
                () -> VanillaDeathPlayerDataCommitter.readBounded(
                        playerData, fileOps));
        Assertions.assertNotNull(exception.getCause());
        Assertions.assertTrue(Files.isRegularFile(playerData));
    }

    @Test
    void injectedPrimaryForceFailureFailsClosed()
            throws IOException {
        Path playerData = temporaryDirectory.resolve("bot.dat");
        Files.write(playerData, new byte[] {1});

        Assertions.assertThrows(
                IOException.class,
                () -> VanillaDeathPlayerDataCommitter.forceRegularFile(
                        playerData,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.FORCE_FILE)));
        Assertions.assertTrue(Files.isRegularFile(playerData));
    }

    @Test
    void injectedPlayerdataDirectoryForceFailureFailsClosed()
            throws IOException {
        Path playerDataDirectory = temporaryDirectory.resolve("playerdata");
        Files.createDirectories(playerDataDirectory);

        Assertions.assertThrows(
                IOException.class,
                () -> VanillaDeathPlayerDataCommitter.forceDirectory(
                        playerDataDirectory,
                        new FaultInjectingDurableFileOps(
                                FaultInjectingDurableFileOps.Point.FORCE_DIRECTORY)));
        Assertions.assertTrue(Files.isDirectory(playerDataDirectory));
    }
}
