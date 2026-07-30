package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryMenuSettlementPolicyTest {
    private static final ItemStackFingerprint EMPTY =
            ItemStackFingerprint.empty();
    private static final ItemStackFingerprint SOURCE =
            item("diamond_helmet", '1');
    private static final ItemStackFingerprint TARGET =
            item("iron_helmet", '2');
    private static final ItemStackFingerprint TEMPORARY =
            item("torch", '3');
    private static final ItemStackFingerprint FIRST_UNRELATED =
            item("cobblestone", '4');
    private static final ItemStackFingerprint SECOND_UNRELATED =
            item("dirt", '5');

    @Test
    void recognizesBothSingleStepEndpointsWithoutClick() {
        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .hotbarToEquipment(
                                snapshot(
                                        0, SOURCE,
                                        39, TARGET),
                                0,
                                39);

        assertDecision(
                plan,
                0,
                InventoryMenuSettlementDecision.Outcome
                        .ALREADY_INITIAL,
                0);
        assertDecision(
                plan,
                1,
                InventoryMenuSettlementDecision.Outcome
                        .ALREADY_FINAL,
                1);
    }

    @Test
    void settlesEveryTwoStepPrefixWithAtMostOneClick() {
        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                snapshot(
                                        10, SOURCE,
                                        39, EMPTY,
                                        2, EMPTY),
                                10,
                                39,
                                2);

        assertDecision(
                plan,
                0,
                InventoryMenuSettlementDecision.Outcome
                        .ALREADY_INITIAL,
                0);
        assertDecision(
                plan,
                1,
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_INITIAL,
                0);
        assertDecision(
                plan,
                2,
                InventoryMenuSettlementDecision.Outcome
                        .ALREADY_FINAL,
                2);
    }

    @Test
    void settlesEveryThreeStepPrefixWithAtMostOneClick() {
        InventoryMenuSwapPlan plan =
                InventoryMenuSwapPlanBuilder
                        .mainToEquipment(
                                snapshot(
                                        10, SOURCE,
                                        39, TARGET,
                                        2, TEMPORARY),
                                10,
                                39,
                                2);

        assertDecision(
                plan,
                0,
                InventoryMenuSettlementDecision.Outcome
                        .ALREADY_INITIAL,
                0);
        assertDecision(
                plan,
                1,
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_INITIAL,
                0);
        assertDecision(
                plan,
                2,
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_FINAL,
                3);
        assertDecision(
                plan,
                3,
                InventoryMenuSettlementDecision.Outcome
                        .ALREADY_FINAL,
                3);
    }

    @Test
    void unknownButConservedLayoutCommitsWithoutClick() {
        InventoryMenuSwapPlan plan = threeStepPlan();
        InventoryMenuSnapshot initial =
                plan.initialSnapshot();
        List<ItemStackFingerprint> rearranged =
                new ArrayList<>(initial.inventorySlots());
        rearranged.set(20, SECOND_UNRELATED);
        rearranged.set(21, FIRST_UNRELATED);
        InventoryMenuSnapshot external =
                new InventoryMenuSnapshot(
                        initial.containerId(),
                        initial.stateId() + 3,
                        6,
                        EMPTY,
                        rearranged);

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        external,
                        stableAuthority(plan, 1));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .SAFE_PREFIX_COMMITTED,
                decision.outcome());
        Assertions.assertTrue(decision.click().isEmpty());
        Assertions.assertEquals(-1, decision.observedPrefix());
    }

    @Test
    void exactButUnownedPrefixIsUnsafe() {
        InventoryMenuSwapPlan plan = threeStepPlan();

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        plan.snapshotAtPrefix(2),
                        stableAuthority(plan, 1));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .UNSAFE,
                decision.outcome());
        Assertions.assertTrue(decision.click().isEmpty());
        Assertions.assertEquals(2, decision.observedPrefix());
    }

    @Test
    void controlOrUnrelatedDriftCannotWashTemporaryPrefix() {
        InventoryMenuSwapPlan plan = threeStepPlan();
        InventoryMenuSnapshot prefix =
                plan.snapshotAtPrefix(1);
        InventoryMenuSnapshot selectedDrift =
                new InventoryMenuSnapshot(
                        prefix.containerId(),
                        prefix.stateId() + 1,
                        5,
                        prefix.cursor(),
                        prefix.inventorySlots());
        List<ItemStackFingerprint> rearranged =
                new ArrayList<>(prefix.inventorySlots());
        rearranged.set(20, SECOND_UNRELATED);
        rearranged.set(21, FIRST_UNRELATED);
        InventoryMenuSnapshot unrelatedDrift =
                new InventoryMenuSnapshot(
                        prefix.containerId(),
                        prefix.stateId() + 2,
                        prefix.selectedHotbar(),
                        prefix.cursor(),
                        rearranged);

        for (InventoryMenuSnapshot drifted :
                List.of(selectedDrift, unrelatedDrift)) {
            InventoryMenuSettlementDecision decision =
                    InventoryMenuSettlementPolicy.decide(
                            plan,
                            drifted,
                            stableAuthority(plan, 0));
            Assertions.assertEquals(
                    InventoryMenuSettlementDecision.Outcome
                            .UNSAFE,
                    decision.outcome());
            Assertions.assertEquals(
                    1, decision.observedPrefix());
            Assertions.assertTrue(
                    decision.click().isEmpty());
        }
    }

    @Test
    void rejectsCursorWrongContainerAndInventoryDelta() {
        InventoryMenuSwapPlan plan = threeStepPlan();
        InventoryMenuSnapshot initial =
                plan.initialSnapshot();
        InventoryMenuSnapshot cursor =
                new InventoryMenuSnapshot(
                        initial.containerId(),
                        initial.stateId(),
                        initial.selectedHotbar(),
                        SOURCE,
                        initial.inventorySlots());
        InventoryMenuSnapshot wrongContainer =
                new InventoryMenuSnapshot(
                        initial.containerId() + 1,
                        initial.stateId(),
                        initial.selectedHotbar(),
                        EMPTY,
                        initial.inventorySlots());
        List<ItemStackFingerprint> changed =
                new ArrayList<>(initial.inventorySlots());
        changed.set(20, SECOND_UNRELATED);
        InventoryMenuSnapshot inventoryDelta =
                new InventoryMenuSnapshot(
                        initial.containerId(),
                        initial.stateId(),
                        initial.selectedHotbar(),
                        EMPTY,
                        changed);

        for (InventoryMenuSnapshot unsafe :
                List.of(cursor, wrongContainer, inventoryDelta)) {
            InventoryMenuSettlementDecision decision =
                    InventoryMenuSettlementPolicy.decide(
                            plan,
                            unsafe,
                            stableAuthority(plan, 1));
            Assertions.assertEquals(
                    InventoryMenuSettlementDecision.Outcome
                            .UNSAFE,
                    decision.outcome());
            Assertions.assertTrue(decision.click().isEmpty());
        }
    }

    @Test
    void rejectsInvalidOrOverbroadPrefixAuthority() {
        InventoryMenuSwapPlan plan = threeStepPlan();

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuPrefixAuthority.stable(
                        plan,
                        -1,
                        plan.initialSnapshot()));
        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> InventoryMenuPrefixAuthority.inFlight(
                        plan,
                        0,
                        2,
                        plan.initialSnapshot(),
                        plan.snapshotAtPrefix(2)));
    }

    @Test
    void stateIdDriftAtKnownPrefixIsUnsafeUntilExplicitlyRebound() {
        InventoryMenuSwapPlan plan = threeStepPlan();
        InventoryMenuSnapshot source =
                plan.snapshotAtPrefix(1);
        InventoryMenuSnapshot drifted =
                new InventoryMenuSnapshot(
                        source.containerId(),
                        source.stateId() + 1,
                        source.selectedHotbar(),
                        source.cursor(),
                        source.inventorySlots());

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        drifted,
                        InventoryMenuPrefixAuthority.stable(
                                plan, 1, source));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .UNSAFE,
                decision.outcome());
        Assertions.assertTrue(decision.click().isEmpty());

        InventoryMenuSettlementDecision rebound =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        drifted,
                        InventoryMenuPrefixAuthority.stable(
                                plan, 1, drifted));
        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_INITIAL,
                rebound.outcome());
        Assertions.assertTrue(rebound.click().isPresent());
    }

    @Test
    void inFlightAuthorityBindsTheExactAdjacentTarget() {
        InventoryMenuSwapPlan plan = threeStepPlan();
        InventoryMenuSnapshot source =
                plan.snapshotAtPrefix(1);
        InventoryMenuSnapshot target =
                plan.snapshotAtPrefix(2);
        InventoryMenuSnapshot observedTarget =
                new InventoryMenuSnapshot(
                        target.containerId(),
                        target.stateId() + 4,
                        target.selectedHotbar(),
                        target.cursor(),
                        target.inventorySlots());

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        observedTarget,
                        InventoryMenuPrefixAuthority.inFlight(
                                plan,
                                1,
                                2,
                                source,
                                observedTarget));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_FINAL,
                decision.outcome());
        Assertions.assertEquals(
                observedTarget,
                decision.observedSnapshot());
        Assertions.assertTrue(decision.click().isPresent());

        InventoryMenuSnapshot driftedAgain =
                new InventoryMenuSnapshot(
                        observedTarget.containerId(),
                        observedTarget.stateId() + 1,
                        observedTarget.selectedHotbar(),
                        observedTarget.cursor(),
                        observedTarget.inventorySlots());
        InventoryMenuSettlementDecision driftedDecision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        driftedAgain,
                        InventoryMenuPrefixAuthority.inFlight(
                                plan,
                                1,
                                2,
                                source,
                                observedTarget));
        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .UNSAFE,
                driftedDecision.outcome());
        Assertions.assertTrue(
                driftedDecision.click().isEmpty());
    }

    @Test
    void structurallyEqualPlanCannotReplayAuthority() {
        InventoryMenuSwapPlan first = threeStepPlan();
        InventoryMenuSwapPlan equalButDistinct =
                threeStepPlan();
        Assertions.assertEquals(first, equalButDistinct);

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        equalButDistinct,
                        equalButDistinct.snapshotAtPrefix(1),
                        stableAuthority(first, 1));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .UNSAFE,
                decision.outcome());
        Assertions.assertTrue(decision.click().isEmpty());
    }

    private static void assertDecision(
            InventoryMenuSwapPlan plan,
            int prefix,
            InventoryMenuSettlementDecision.Outcome expected,
            int expectedEndpoint) {
        InventoryMenuSnapshot actual =
                plan.snapshotAtPrefix(prefix);
        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        actual,
                        stableAuthority(plan, prefix));
        Assertions.assertEquals(expected, decision.outcome());
        Assertions.assertEquals(
                prefix, decision.observedPrefix());
        Assertions.assertEquals(
                actual, decision.observedSnapshot());
        Assertions.assertTrue(decision.click().stream().count() <= 1L);
        InventoryMenuSnapshot settled =
                decision.click()
                        .map(InventoryMenuClickStep::after)
                        .orElse(actual);
        Assertions.assertTrue(
                plan.snapshotAtPrefix(expectedEndpoint)
                        .layoutEqualsIgnoringState(settled));
    }

    private static InventoryMenuPrefixAuthority stableAuthority(
            InventoryMenuSwapPlan plan, int prefix) {
        return InventoryMenuPrefixAuthority.stable(
                plan,
                prefix,
                plan.snapshotAtPrefix(prefix));
    }

    private static InventoryMenuSwapPlan threeStepPlan() {
        return InventoryMenuSwapPlanBuilder
                .mainToEquipment(
                        snapshot(
                                10, SOURCE,
                                39, TARGET,
                                2, TEMPORARY,
                                20, FIRST_UNRELATED,
                                21, SECOND_UNRELATED),
                        10,
                        39,
                        2);
    }

    private static InventoryMenuSnapshot snapshot(
            Object... slotAndFingerprintPairs) {
        List<ItemStackFingerprint> slots =
                new ArrayList<>(41);
        for (int index = 0; index < 41; index++) {
            slots.add(EMPTY);
        }
        for (int index = 0;
                index < slotAndFingerprintPairs.length;
                index += 2) {
            slots.set(
                    (Integer) slotAndFingerprintPairs[index],
                    (ItemStackFingerprint)
                            slotAndFingerprintPairs[index + 1]);
        }
        return new InventoryMenuSnapshot(
                0, 17, 4, EMPTY, slots);
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
