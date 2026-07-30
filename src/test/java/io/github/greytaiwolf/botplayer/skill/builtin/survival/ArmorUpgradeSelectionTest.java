package io.github.greytaiwolf.botplayer.skill.builtin.survival;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.Optional;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class ArmorUpgradeSelectionTest {
    private static final String DIGEST = "b".repeat(64);

    @Test
    void mapsArmorSlotsToVanillaInventoryIndices() {
        Assertions.assertEquals(
                39,
                ArmorUpgradeSelection.targetInventorySlotFor(
                        EquipmentSlotKind.HEAD));
        Assertions.assertEquals(
                38,
                ArmorUpgradeSelection.targetInventorySlotFor(
                        EquipmentSlotKind.CHEST));
        Assertions.assertEquals(
                37,
                ArmorUpgradeSelection.targetInventorySlotFor(
                        EquipmentSlotKind.LEGS));
        Assertions.assertEquals(
                36,
                ArmorUpgradeSelection.targetInventorySlotFor(
                        EquipmentSlotKind.FEET));
    }

    @Test
    void freezesMatchingHotbarSourceAndArmorTarget() {
        EquipmentCandidate candidate =
                candidate(
                        2,
                        EquipmentSlotKind.CHEST,
                        false,
                        false);

        ArmorUpgradeSelection selection =
                new ArmorUpgradeSelection(
                        2, 38, candidate);

        Assertions.assertEquals(2, selection.sourceInventorySlot());
        Assertions.assertEquals(38, selection.targetInventorySlot());
        Assertions.assertEquals(candidate, selection.candidate());
    }

    @Test
    void rejectsMismatchedOrBindingCandidate() {
        EquipmentCandidate chest =
                candidate(
                        2,
                        EquipmentSlotKind.CHEST,
                        false,
                        false);
        EquipmentCandidate binding =
                candidate(
                        2,
                        EquipmentSlotKind.CHEST,
                        false,
                        true);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ArmorUpgradeSelection(
                        2, 39, chest));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new ArmorUpgradeSelection(
                        2, 38, binding));
    }

    @Test
    void acceptsCarriedSlotsAndRejectsEquipmentSources() {
        for (int source : new int[] {0, 8, 9, 35}) {
            EquipmentCandidate candidate =
                    candidate(
                            source,
                            EquipmentSlotKind.CHEST,
                            false,
                            false);
            Assertions.assertEquals(
                    source,
                    new ArmorUpgradeSelection(
                                    source, 38, candidate)
                            .sourceInventorySlot());
        }
        for (int source : new int[] {-1, 36, 40}) {
            Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> {
                        EquipmentCandidate candidate =
                                candidate(
                                        source,
                                        EquipmentSlotKind.CHEST,
                                        false,
                                        false);
                        new ArmorUpgradeSelection(
                                source, 38, candidate);
                    });
        }
    }

    @Test
    void rejectsNonArmorMappingRequest() {
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> ArmorUpgradeSelection
                        .targetInventorySlotFor(
                                EquipmentSlotKind.OFFHAND));
    }

    private static EquipmentCandidate candidate(
            int inventorySlot,
            EquipmentSlotKind targetSlot,
            boolean targetBlocked,
            boolean candidateBinds) {
        return new EquipmentCandidate(
                inventorySlot,
                ItemStackFingerprint.of(
                        new ResourceId(
                                "minecraft:test_armor"),
                        1,
                        0,
                        DIGEST),
                targetSlot,
                Optional.empty(),
                0,
                3.0D,
                2.0D,
                0.0D,
                100,
                true,
                true,
                targetBlocked,
                candidateBinds);
    }
}
