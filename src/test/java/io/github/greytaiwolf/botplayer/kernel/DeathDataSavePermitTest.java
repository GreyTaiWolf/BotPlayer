package io.github.greytaiwolf.botplayer.kernel;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class DeathDataSavePermitTest {
    @Test
    void allowsOnlyTheFirstSaveHeadAndSuppressesRecursion() {
        DeathDataSavePermit permit = new DeathDataSavePermit();
        permit.arm(DeathDataSavePermit.HandoffMode.OMIT);

        Assertions.assertFalse(permit.shouldSuppress(true));
        Assertions.assertTrue(
                permit.omitHandoffWhileSerializing());
        Assertions.assertTrue(permit.shouldSuppress(false));
        Assertions.assertTrue(permit.finish());
        Assertions.assertFalse(permit.armedOrSerializing());
    }

    @Test
    void unusedPermitCannotMasqueradeAsASerializedSave() {
        DeathDataSavePermit permit = new DeathDataSavePermit();
        permit.arm(DeathDataSavePermit.HandoffMode.INCLUDE);

        Assertions.assertFalse(permit.finish());
        Assertions.assertFalse(
                permit.omitHandoffWhileSerializing());
    }
}
