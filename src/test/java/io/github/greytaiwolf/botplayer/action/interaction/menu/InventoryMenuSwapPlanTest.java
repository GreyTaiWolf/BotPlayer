package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryMenuSwapPlanTest {
    private static final ItemStackFingerprint EMPTY =
            ItemStackFingerprint.empty();
    private static final ItemStackFingerprint SOURCE =
            item("diamond_helmet", '1');
    private static final ItemStackFingerprint TARGET =
            item("iron_helmet", '2');
    private static final ItemStackFingerprint TEMPORARY =
            item("torch", '3');
    private static final ItemStackFingerprint UNRELATED =
            item("cobblestone", '4');

    @Test
    void buildsThreeExactStepsAndRestoresTemporaryHotbar() {
        InventoryMenuSnapshot initial = snapshot(
                10, SOURCE,
                39, TARGET,
                2, TEMPORARY,
                20, UNRELATED);

        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 39, 2);

        Assertions.assertEquals(
                InventoryMenuSwapPlan.Operation
                        .MAIN_TO_EQUIPMENT,
                plan.operation());
        Assertions.assertEquals(3, plan.orderedSteps().size());
        Assertions.assertEquals(
                List.of(10, 5, 10),
                plan.orderedSteps().stream()
                        .map(InventoryMenuClickStep::menuSlot)
                        .toList());
        Assertions.assertEquals(
                List.of(2, 2, 2),
                plan.orderedSteps().stream()
                        .map(InventoryMenuClickStep::hotbarButton)
                        .toList());
        Assertions.assertEquals(
                List.of(2, 10, 39),
                plan.touchedInventorySlots());
        Assertions.assertEquals(
                TARGET, plan.finalSnapshot().itemAt(10));
        Assertions.assertEquals(
                SOURCE, plan.finalSnapshot().itemAt(39));
        Assertions.assertEquals(
                TEMPORARY, plan.finalSnapshot().itemAt(2));
        Assertions.assertEquals(
                UNRELATED, plan.finalSnapshot().itemAt(20));
        Assertions.assertEquals(
                initial.stateId(),
                plan.finalSnapshot().stateId());
        Assertions.assertTrue(
                plan.finalSnapshot().cursor().isEmpty());

        InventoryMenuSnapshot expectedBefore = initial;
        for (InventoryMenuClickStep step :
                plan.orderedSteps()) {
            Assertions.assertEquals(
                    expectedBefore, step.before());
            expectedBefore = step.after();
        }
        Assertions.assertEquals(
                expectedBefore, plan.finalSnapshot());
    }

    @Test
    void buildsOneStepMainToHotbarPlan() {
        InventoryMenuSnapshot initial = snapshot(
                9, SOURCE,
                0, TEMPORARY);

        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .mainToHotbar(initial, 9, 0);

        Assertions.assertEquals(
                InventoryMenuSwapPlan.Operation.MAIN_TO_HOTBAR,
                plan.operation());
        Assertions.assertEquals(1, plan.orderedSteps().size());
        InventoryMenuClickStep step =
                plan.orderedSteps().getFirst();
        Assertions.assertEquals(9, step.menuSlot());
        Assertions.assertEquals(0, step.hotbarButton());
        Assertions.assertEquals(
                TEMPORARY, plan.finalSnapshot().itemAt(9));
        Assertions.assertEquals(
                SOURCE, plan.finalSnapshot().itemAt(0));
    }

    @Test
    void acceptsArmorAndOffhandTargetsWithNativeMenuSlots() {
        InventoryMenuSnapshot armor = snapshot(
                11, SOURCE,
                36, TARGET,
                1, TEMPORARY);
        InventoryMenuSnapshot offhand = snapshot(
                11, SOURCE,
                40, TARGET,
                1, TEMPORARY);

        InventoryMenuSwapPlan armorPlan =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(armor, 11, 36, 1);
        InventoryMenuSwapPlan offhandPlan =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                offhand, 11, 40, 1);

        Assertions.assertEquals(
                8, armorPlan.orderedSteps().get(1).menuSlot());
        Assertions.assertEquals(
                45,
                offhandPlan.orderedSteps().get(1).menuSlot());
    }

    @Test
    void buildsSingleClickHotbarToEquipmentPlan() {
        InventoryMenuSnapshot initial = snapshot(
                3, SOURCE,
                39, TARGET,
                20, UNRELATED);

        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .hotbarToEquipment(initial, 3, 39);

        Assertions.assertEquals(
                InventoryMenuSwapPlan.Operation
                        .HOTBAR_TO_EQUIPMENT,
                plan.operation());
        Assertions.assertEquals(1, plan.orderedSteps().size());
        Assertions.assertEquals(
                5, plan.orderedSteps().getFirst().menuSlot());
        Assertions.assertEquals(
                3, plan.orderedSteps().getFirst().hotbarButton());
        Assertions.assertEquals(
                TARGET, plan.finalSnapshot().itemAt(3));
        Assertions.assertEquals(
                SOURCE, plan.finalSnapshot().itemAt(39));
        Assertions.assertEquals(
                UNRELATED, plan.finalSnapshot().itemAt(20));
    }

    @Test
    void fullSnapshotsDetectUnrelatedLayoutChanges() {
        InventoryMenuSnapshot initial = snapshot(
                10, SOURCE,
                39, TARGET,
                2, TEMPORARY,
                20, UNRELATED);
        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 39, 2);
        InventoryMenuSnapshot sameLayoutNewState =
                new InventoryMenuSnapshot(
                        initial.containerId(),
                        initial.stateId() + 7,
                        initial.selectedHotbar(),
                        initial.cursor(),
                        initial.inventorySlots());
        InventoryMenuSnapshot unrelatedChanged =
                replace(
                        sameLayoutNewState,
                        20,
                        item("dirt", '5'));

        Assertions.assertTrue(
                initial.layoutEqualsIgnoringState(
                        sameLayoutNewState));
        Assertions.assertFalse(
                initial.layoutEqualsIgnoringState(
                        unrelatedChanged));
        Assertions.assertFalse(
                plan.orderedSteps().getFirst().after()
                        .layoutEqualsIgnoringState(initial));
        InventoryMenuClickStep first =
                plan.orderedSteps().getFirst();
        InventoryMenuSnapshot corruptedAfter =
                replace(first.after(), 20, item("dirt", '5'));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryMenuClickStep(
                        first.menuSlot(),
                        first.hotbarButton(),
                        first.before(),
                        corruptedAfter));
    }

    @Test
    void rejectsWrongRolesAndReadOnlyCraftClicks() {
        InventoryMenuSnapshot initial = snapshot(
                10, SOURCE,
                39, TARGET,
                2, TEMPORARY);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToHotbar(initial, 2, 0));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 20, 2));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 39, 9));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToHotbar(initial, 12, 0));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryMenuClickStep(
                        0,
                        2,
                        initial,
                        initial));
    }

    @Test
    void removesUnobservableClicksWhenTemporaryMatchesOneSide() {
        InventoryMenuSnapshot sourceEqualsTarget =
                snapshot(10, SOURCE, 39, SOURCE, 2, TEMPORARY);
        InventoryMenuSnapshot sourceEqualsTemporary =
                snapshot(10, SOURCE, 39, TARGET, 2, SOURCE);
        InventoryMenuSnapshot targetEqualsTemporary =
                snapshot(10, SOURCE, 39, TARGET, 2, TARGET);
        InventoryMenuSnapshot emptyTargetAndTemporary =
                snapshot(10, SOURCE);

        InventoryMenuSwapPlan sourceMatchPlan =
                InventoryMenuSwapPlanBuilder.mainToEquipment(
                        sourceEqualsTemporary, 10, 39, 2);
        InventoryMenuSwapPlan targetMatchPlan =
                InventoryMenuSwapPlanBuilder.mainToEquipment(
                        targetEqualsTemporary, 10, 39, 2);
        InventoryMenuSwapPlan emptyPlan =
                InventoryMenuSwapPlanBuilder.mainToEquipment(
                        emptyTargetAndTemporary, 10, 39, 2);

        Assertions.assertEquals(
                List.of(5, 10),
                sourceMatchPlan.orderedSteps().stream()
                        .map(InventoryMenuClickStep::menuSlot)
                        .toList());
        Assertions.assertEquals(
                List.of(10, 5),
                targetMatchPlan.orderedSteps().stream()
                        .map(InventoryMenuClickStep::menuSlot)
                        .toList());
        Assertions.assertEquals(
                List.of(10, 5),
                emptyPlan.orderedSteps().stream()
                        .map(InventoryMenuClickStep::menuSlot)
                        .toList());
        Assertions.assertEquals(
                SOURCE, emptyPlan.finalSnapshot().itemAt(39));
        Assertions.assertTrue(
                emptyPlan.finalSnapshot().itemAt(10).isEmpty());
        Assertions.assertTrue(
                emptyPlan.finalSnapshot().itemAt(2).isEmpty());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                sourceEqualsTarget, 10, 39, 2));
    }

    @Test
    void rejectsNonEmptyCursor() {
        InventoryMenuSnapshot nonEmptyCursor =
                new InventoryMenuSnapshot(
                        0,
                        17,
                        4,
                        SOURCE,
                        emptySlots());

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToHotbar(
                                nonEmptyCursor, 10, 2));
    }

    @Test
    void rejectsClickAndUniqueSlotLimitOverflow() {
        InventoryMenuSnapshot initial = snapshot(
                10, SOURCE,
                39, TARGET,
                2, TEMPORARY);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                initial,
                                10,
                                39,
                                2,
                                new InventoryMenuTransactionLimits(
                                        2, 3)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                initial,
                                10,
                                39,
                                2,
                                new InventoryMenuTransactionLimits(
                                        3, 2)));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryMenuTransactionLimits(
                        InventoryMenuTransactionLimits
                                        .HARD_MAX_CLICKS
                                + 1,
                        3));
    }

    @Test
    void outputIsImmutableAndStableAcrossRepeatedBuilds() {
        List<ItemStackFingerprint> mutableSlots =
                emptySlots();
        mutableSlots.set(10, SOURCE);
        mutableSlots.set(39, TARGET);
        mutableSlots.set(2, TEMPORARY);
        InventoryMenuSnapshot initial =
                new InventoryMenuSnapshot(
                        0, 17, 4, EMPTY, mutableSlots);

        InventoryMenuSwapPlan first =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 39, 2);
        InventoryMenuSwapPlan second =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 39, 2);
        mutableSlots.set(10, UNRELATED);

        Assertions.assertEquals(first, second);
        Assertions.assertEquals(
                SOURCE, initial.itemAt(10));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> initial.inventorySlots().set(0, SOURCE));
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> first.orderedSteps().clear());
        Assertions.assertThrows(
                UnsupportedOperationException.class,
                () -> first.touchedInventorySlots().clear());
    }

    @Test
    void choosesStableNonSelectedTemporaryHotbarSlot() {
        InventoryMenuSnapshot withEmpty = snapshot(
                10, SOURCE,
                39, TARGET,
                0, TEMPORARY);
        InventoryMenuSwapPlan emptyPreferred =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                withEmpty, 10, 39);

        Assertions.assertEquals(
                1,
                emptyPreferred
                        .orderedSteps()
                        .getFirst()
                        .hotbarButton());
        Assertions.assertEquals(
                1,
                InventoryMenuSwapPlanBuilder
                        .temporaryHotbarSlot(withEmpty));

        List<ItemStackFingerprint> fullSlots =
                emptySlots();
        fullSlots.set(10, SOURCE);
        fullSlots.set(39, TARGET);
        for (int hotbar = 0; hotbar <= 8; hotbar++) {
            fullSlots.set(
                    hotbar,
                    item(
                            "full_hotbar_" + hotbar,
                            (char) ('0' + hotbar)));
        }
        InventoryMenuSnapshot full =
                new InventoryMenuSnapshot(
                        0, 17, 0, EMPTY, fullSlots);
        InventoryMenuSwapPlan fullFallback =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                full, 10, 39);

        Assertions.assertEquals(
                1,
                fullFallback
                        .orderedSteps()
                        .getFirst()
                        .hotbarButton());
        Assertions.assertEquals(
                1,
                InventoryMenuSwapPlanBuilder
                        .temporaryHotbarSlot(full));
    }

    @Test
    void reversedStepRoundTripsAndRepeatedPrefixIsRejected() {
        InventoryMenuSnapshot initial = snapshot(
                10, SOURCE,
                39, TARGET,
                2, TEMPORARY);
        InventoryMenuSwapPlan normal =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(initial, 10, 39, 2);
        InventoryMenuClickStep first =
                normal.orderedSteps().getFirst();
        InventoryMenuClickStep reversed = first.reversed();
        InventoryMenuClickStep unrelatedThird =
                InventoryMenuSwapPlanBuilder
                        .hotbarToEquipment(initial, 2, 39)
                        .orderedSteps()
                        .getFirst();

        Assertions.assertEquals(first, reversed.reversed());
        Assertions.assertEquals(
                initial, reversed.after());
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryMenuSwapPlan(
                        InventoryMenuSwapPlan.Operation
                                .MAIN_TO_EQUIPMENT,
                        initial,
                        unrelatedThird.after(),
                        List.of(
                                first,
                                reversed,
                                unrelatedThird),
                        InventoryMenuTransactionLimits.defaults()));
    }

    @Test
    void genericSequenceAcceptsOneThroughSixteenClicks() {
        InventoryMenuSnapshot initial = numberedSnapshot(
                0, 9, 10, 11, 12, 13, 14, 15);
        InventoryMenuSwapPlan single =
                InventoryMenuSwapPlanBuilder.swapSequence(
                        initial,
                        List.of(new InventoryMenuSwapInstruction(
                                9, 0)));
        List<InventoryMenuSwapInstruction> instructions =
                new ArrayList<>();
        for (int index = 0;
                index < InventoryMenuTransactionLimits
                        .HARD_MAX_CLICKS;
                index++) {
            instructions.add(
                    new InventoryMenuSwapInstruction(
                            9 + index % 7, 0));
        }

        Assertions.assertEquals(
                InventoryMenuSwapPlan.Operation.SWAP_SEQUENCE,
                single.operation());
        Assertions.assertEquals(1, single.orderedSteps().size());
        InventoryMenuSwapPlan maximum = null;
        for (int clickCount = 1;
                clickCount
                        <= InventoryMenuTransactionLimits
                                .HARD_MAX_CLICKS;
                clickCount++) {
            InventoryMenuSwapPlan plan =
                    InventoryMenuSwapPlanBuilder.swapSequence(
                            initial,
                            instructions.subList(
                                    0, clickCount));
            Assertions.assertEquals(
                    InventoryMenuSwapPlan.Operation
                            .SWAP_SEQUENCE,
                    plan.operation());
            Assertions.assertEquals(
                    clickCount, plan.orderedSteps().size());
            Assertions.assertTrue(
                    plan.finalSnapshot()
                            .inventoryMultisetEquals(initial));
            maximum = plan;
        }
        Assertions.assertTrue(maximum != null);
        Assertions.assertEquals(
                List.of(0, 9, 10, 11, 12, 13, 14, 15),
                maximum.touchedInventorySlots());
        Assertions.assertEquals(
                InventoryMenuTransactionLimits.hardMaximum(),
                maximum.limits());
    }

    @Test
    void genericSequenceRejectsEmptyClickAndSlotOverflow() {
        InventoryMenuSnapshot eightSlots = numberedSnapshot(
                0, 9, 10, 11, 12, 13, 14, 15);
        List<InventoryMenuSwapInstruction> tooMany =
                new ArrayList<>();
        for (int index = 0;
                index
                        <= InventoryMenuTransactionLimits
                                .HARD_MAX_CLICKS;
                index++) {
            tooMany.add(new InventoryMenuSwapInstruction(
                    9 + index % 7, 0));
        }
        InventoryMenuSnapshot nineSlots = numberedSnapshot(
                0, 9, 10, 11, 12, 13, 14, 15, 16);
        List<InventoryMenuSwapInstruction> tooWide =
                new ArrayList<>();
        for (int slot = 9; slot <= 16; slot++) {
            tooWide.add(new InventoryMenuSwapInstruction(
                    slot, 0));
        }
        List<InventoryMenuSwapInstruction> withNull =
                new ArrayList<>();
        withNull.add(new InventoryMenuSwapInstruction(9, 0));
        withNull.add(null);

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .swapSequence(eightSlots, List.of()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .swapSequence(eightSlots, tooMany));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .swapSequence(nineSlots, tooWide));
        Assertions.assertThrows(
                NullPointerException.class,
                () -> InventoryMenuSwapPlanBuilder
                        .swapSequence(
                                eightSlots,
                                withNull));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryMenuSwapInstruction(0, 0));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> new InventoryMenuSwapInstruction(9, 9));
    }

    private static InventoryMenuSnapshot snapshot(
            Object... slotAndFingerprintPairs) {
        if (slotAndFingerprintPairs.length % 2 != 0) {
            throw new IllegalArgumentException(
                    "slot/fingerprint pairs are incomplete");
        }
        List<ItemStackFingerprint> slots = emptySlots();
        for (int index = 0;
                index < slotAndFingerprintPairs.length;
                index += 2) {
            int slot = (Integer) slotAndFingerprintPairs[index];
            ItemStackFingerprint fingerprint =
                    (ItemStackFingerprint)
                            slotAndFingerprintPairs[index + 1];
            slots.set(slot, fingerprint);
        }
        return new InventoryMenuSnapshot(
                0, 17, 4, EMPTY, slots);
    }

    private static InventoryMenuSnapshot replace(
            InventoryMenuSnapshot source,
            int inventorySlot,
            ItemStackFingerprint fingerprint) {
        List<ItemStackFingerprint> slots =
                new ArrayList<>(source.inventorySlots());
        slots.set(inventorySlot, fingerprint);
        return new InventoryMenuSnapshot(
                source.containerId(),
                source.stateId(),
                source.selectedHotbar(),
                source.cursor(),
                slots);
    }

    private static InventoryMenuSnapshot numberedSnapshot(
            int... inventorySlots) {
        List<ItemStackFingerprint> slots = emptySlots();
        for (int index = 0;
                index < inventorySlots.length;
                index++) {
            int inventorySlot = inventorySlots[index];
            slots.set(
                    inventorySlot,
                    item(
                            "generic_token_" + inventorySlot,
                            "0123456789abcdef".charAt(index)));
        }
        return new InventoryMenuSnapshot(
                0, 17, 4, EMPTY, slots);
    }

    private static List<ItemStackFingerprint> emptySlots() {
        List<ItemStackFingerprint> slots = new ArrayList<>(41);
        for (int index = 0; index < 41; index++) {
            slots.add(EMPTY);
        }
        return slots;
    }

    private static ItemStackFingerprint item(
            String path, char digestDigit) {
        return ItemStackFingerprint.of(
                new ResourceId("minecraft:" + path),
                1,
                0,
                String.valueOf(digestDigit).repeat(64));
    }
}
