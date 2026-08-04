package io.github.greytaiwolf.botplayer.action.interaction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryStackMultisetDigestTest {
    private static final ItemStackFingerprint EMPTY =
            ItemStackFingerprint.empty();
    private static final ItemStackFingerprint BREAD =
            ItemStackFingerprint.of(
                    new ResourceId("minecraft:bread"),
                    3,
                    0,
                    "1".repeat(64));
    private static final ItemStackFingerprint SWORD =
            ItemStackFingerprint.of(
                    new ResourceId("minecraft:iron_sword"),
                    1,
                    17,
                    "2".repeat(64));

    @Test
    void inventoryMultisetDigestIgnoresSlotsButPreservesEveryStackField() {
        List<ItemStackFingerprint> before =
                new ArrayList<>(Collections.nCopies(41, EMPTY));
        before.set(9, BREAD);
        before.set(0, SWORD);

        List<ItemStackFingerprint> swapped = new ArrayList<>(before);
        Collections.swap(swapped, 9, 0);
        String digest =
                InventoryStackMultisetDigest.sha256(before);
        Assertions.assertEquals(64, digest.length());
        Assertions.assertEquals(
                digest,
                InventoryStackMultisetDigest.sha256(swapped));

        List<ItemStackFingerprint> countChanged =
                new ArrayList<>(swapped);
        countChanged.set(
                0,
                ItemStackFingerprint.of(
                        new ResourceId("minecraft:bread"),
                        2,
                        0,
                        "1".repeat(64)));
        Assertions.assertNotEquals(
                digest,
                InventoryStackMultisetDigest.sha256(
                        countChanged));

        List<ItemStackFingerprint> componentsChanged =
                new ArrayList<>(swapped);
        componentsChanged.set(
                9,
                ItemStackFingerprint.of(
                        new ResourceId("minecraft:iron_sword"),
                        1,
                        17,
                        "3".repeat(64)));
        Assertions.assertNotEquals(
                digest,
                InventoryStackMultisetDigest.sha256(
                        componentsChanged));

        List<ItemStackFingerprint> damageChanged =
                new ArrayList<>(swapped);
        damageChanged.set(
                9,
                ItemStackFingerprint.of(
                        new ResourceId("minecraft:iron_sword"),
                        1,
                        18,
                        "2".repeat(64)));
        Assertions.assertNotEquals(
                digest,
                InventoryStackMultisetDigest.sha256(
                        damageChanged));

        Assertions.assertNotEquals(
                digest,
                InventoryStackMultisetDigest.sha256(
                        swapped.subList(0, 40)));
    }

    @Test
    void inventoryMultisetDigestRejectsIncompleteInputs() {
        List<ItemStackFingerprint> missing = null;
        List<ItemStackFingerprint> withNull = new ArrayList<>();
        withNull.add(BREAD);
        withNull.add(null);
        Assertions.assertThrows(
                NullPointerException.class,
                () -> InventoryStackMultisetDigest.sha256(
                        missing));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> InventoryStackMultisetDigest.sha256(
                        withNull));
    }
}
