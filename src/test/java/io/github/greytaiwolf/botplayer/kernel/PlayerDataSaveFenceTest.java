package io.github.greytaiwolf.botplayer.kernel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class PlayerDataSaveFenceTest {
    @Test
    void ordinarySaveDoesNotConsumeTheOneShotRemovalGate() {
        PlayerDataSaveFence fence = new PlayerDataSaveFence();
        fence.armNextRemovalSave();

        Assertions.assertFalse(fence.shouldSuppressOrdinarySave());
        Assertions.assertTrue(fence.consumeRemovalSaveSuppression());
        Assertions.assertFalse(fence.consumeRemovalSaveSuppression());
    }

    @Test
    void persistentFenceSuppressesBothSavePathsWithoutBeingConsumed() {
        PlayerDataSaveFence fence = new PlayerDataSaveFence();
        fence.armPersistentSaveFence();

        Assertions.assertTrue(fence.shouldSuppressOrdinarySave());
        Assertions.assertTrue(fence.consumeRemovalSaveSuppression());
        Assertions.assertTrue(fence.shouldSuppressOrdinarySave());
        Assertions.assertTrue(fence.consumeRemovalSaveSuppression());
    }

    @Test
    void releasingOneFenceReportsOtherOwnership() {
        PlayerDataSaveFence fence = new PlayerDataSaveFence();
        fence.armNextRemovalSave();
        fence.armDisconnectPreSaveFence();

        Assertions.assertFalse(fence.releaseDisconnectPreSaveFence());
        Assertions.assertTrue(fence.consumeRemovalSaveSuppression());
        Assertions.assertFalse(fence.consumeRemovalSaveSuppression());
    }
}
