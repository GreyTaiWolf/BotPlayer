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

    @Test
    void fiveStepPlanSettlesAcrossTicksToFixedNearestEndpoint() {
        InventoryMenuSwapPlan plan = fiveStepPlan();

        assertSettlementPath(plan, 2, 0);
        assertSettlementPath(plan, 3, 5);
    }

    @Test
    void fiveStepPlanRecognizesExactTargetAfterClickThrows() {
        InventoryMenuSwapPlan plan = fiveStepPlan();
        InventoryMenuSnapshot source = withState(
                plan.snapshotAtPrefix(2), 40);
        InventoryMenuSnapshot target = withState(
                plan.snapshotAtPrefix(1), 41);
        InventoryMenuSettlementDecision initialDecision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        source,
                        InventoryMenuPrefixAuthority.stable(
                                plan, 2, source));
        InventoryMenuSettlementCursor cursor =
                initialDecision.settlementCursor()
                        .orElseThrow();

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        cursor,
                        target,
                        InventoryMenuPrefixAuthority.inFlight(
                                plan,
                                2,
                                1,
                                source,
                                target));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_INITIAL,
                decision.outcome());
        InventoryMenuClickStep next =
                decision.click().orElseThrow();
        Assertions.assertTrue(
                next.before().layoutEqualsIgnoringState(target));
        Assertions.assertTrue(
                next.after().layoutEqualsIgnoringState(
                        plan.initialSnapshot()));
        Assertions.assertTrue(
                next.after().inventoryMultisetEquals(
                        plan.initialSnapshot()));
        Assertions.assertEquals(
                InventoryMenuSettlementCursor.Endpoint.INITIAL,
                decision.settlementCursor()
                        .orElseThrow()
                        .endpoint());
        Assertions.assertEquals(
                1,
                decision.settlementCursor()
                        .orElseThrow()
                        .confirmedPrefix());
    }

    @Test
    void fiveStepPlanKeepsSourceAfterBeforeMutationThrow() {
        InventoryMenuSwapPlan plan = fiveStepPlan();
        InventoryMenuSnapshot source = withState(
                plan.snapshotAtPrefix(2), 40);
        InventoryMenuSnapshot target = withState(
                plan.snapshotAtPrefix(1), 41);
        InventoryMenuSettlementDecision initialDecision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        source,
                        InventoryMenuPrefixAuthority.stable(
                                plan, 2, source));
        InventoryMenuSettlementCursor cursor =
                initialDecision.settlementCursor()
                        .orElseThrow();

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        cursor,
                        source,
                        InventoryMenuPrefixAuthority.inFlight(
                                plan,
                                2,
                                1,
                                source,
                                target));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome
                        .CLICK_TO_INITIAL,
                decision.outcome());
        Assertions.assertEquals(
                2, decision.observedPrefix());
        Assertions.assertEquals(
                source, decision.observedSnapshot());
        Assertions.assertTrue(
                decision.click().orElseThrow()
                        .after()
                        .layoutEqualsIgnoringState(target));
        Assertions.assertTrue(
                cursor
                        == decision.settlementCursor()
                                .orElseThrow());
    }

    @Test
    void frozenEndpointRejectsOppositeAdjacentTarget() {
        InventoryMenuSwapPlan plan = fiveStepPlan();
        InventoryMenuSnapshot source = withState(
                plan.snapshotAtPrefix(2), 40);
        InventoryMenuSnapshot oppositeTarget = withState(
                plan.snapshotAtPrefix(3), 41);
        InventoryMenuSettlementCursor cursor =
                InventoryMenuSettlementPolicy.decide(
                                plan,
                                source,
                                InventoryMenuPrefixAuthority
                                        .stable(plan, 2, source))
                        .settlementCursor()
                        .orElseThrow();

        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        cursor,
                        oppositeTarget,
                        InventoryMenuPrefixAuthority.inFlight(
                                plan,
                                2,
                                3,
                                source,
                                oppositeTarget));

        Assertions.assertEquals(
                InventoryMenuSettlementCursor.Endpoint.INITIAL,
                cursor.endpoint());
        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome.UNSAFE,
                decision.outcome());
        Assertions.assertEquals(3, decision.observedPrefix());
        Assertions.assertTrue(decision.click().isEmpty());
        Assertions.assertTrue(
                decision.settlementCursor().isEmpty());
    }

    @Test
    void fiveStepPlanRejectsUnownedPrefixAndConservationLoss() {
        InventoryMenuSwapPlan plan = fiveStepPlan();
        InventoryMenuSettlementDecision unowned =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        plan.snapshotAtPrefix(3),
                        stableAuthority(plan, 2));
        List<ItemStackFingerprint> changed =
                new ArrayList<>(
                        plan.snapshotAtPrefix(2)
                                .inventorySlots());
        changed.set(20, item("unexpected_item", '8'));
        InventoryMenuSnapshot lostConservation =
                new InventoryMenuSnapshot(
                        plan.initialSnapshot().containerId(),
                        70,
                        plan.initialSnapshot().selectedHotbar(),
                        EMPTY,
                        changed);
        InventoryMenuSettlementDecision changedDecision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        lostConservation,
                        stableAuthority(plan, 2));

        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome.UNSAFE,
                unowned.outcome());
        Assertions.assertEquals(3, unowned.observedPrefix());
        Assertions.assertEquals(
                InventoryMenuSettlementDecision.Outcome.UNSAFE,
                changedDecision.outcome());
        Assertions.assertFalse(
                lostConservation.inventoryMultisetEquals(
                        plan.initialSnapshot()));
        Assertions.assertTrue(changedDecision.click().isEmpty());
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

    private static void assertSettlementPath(
            InventoryMenuSwapPlan plan,
            int startingPrefix,
            int endpointPrefix) {
        int currentPrefix = startingPrefix;
        InventoryMenuSnapshot actual = withState(
                plan.snapshotAtPrefix(currentPrefix), 100);
        InventoryMenuSettlementDecision decision =
                InventoryMenuSettlementPolicy.decide(
                        plan,
                        actual,
                        InventoryMenuPrefixAuthority.stable(
                                plan,
                                currentPrefix,
                                actual));
        InventoryMenuSettlementCursor cursor =
                decision.settlementCursor().orElseThrow();
        while (currentPrefix != endpointPrefix) {
            boolean towardInitial = endpointPrefix == 0;
            Assertions.assertEquals(
                    towardInitial
                            ? InventoryMenuSettlementDecision
                                    .Outcome.CLICK_TO_INITIAL
                            : InventoryMenuSettlementDecision
                                    .Outcome.CLICK_TO_FINAL,
                    decision.outcome());
            Assertions.assertEquals(
                    currentPrefix,
                    decision.observedPrefix());
            InventoryMenuClickStep click =
                    decision.click().orElseThrow();
            int nextPrefix = currentPrefix
                    + (towardInitial ? -1 : 1);
            Assertions.assertTrue(
                    click.before().layoutEqualsIgnoringState(
                            actual));
            Assertions.assertTrue(
                    click.after().layoutEqualsIgnoringState(
                            plan.snapshotAtPrefix(nextPrefix)));
            Assertions.assertTrue(
                    click.after().inventoryMultisetEquals(
                            plan.initialSnapshot()));
            actual = withState(
                    click.after(), actual.stateId() + 1);
            currentPrefix = nextPrefix;
            cursor = cursor.advanceAfterProposedClick();
            decision = InventoryMenuSettlementPolicy.decide(
                    cursor,
                    actual,
                    InventoryMenuPrefixAuthority.stable(
                            plan,
                            currentPrefix,
                            actual));
        }

        Assertions.assertEquals(
                endpointPrefix == 0
                        ? InventoryMenuSettlementDecision
                                .Outcome.ALREADY_INITIAL
                        : InventoryMenuSettlementDecision
                                .Outcome.ALREADY_FINAL,
                decision.outcome());
        Assertions.assertTrue(decision.click().isEmpty());
        Assertions.assertEquals(
                endpointPrefix,
                decision.settlementCursor()
                        .orElseThrow()
                        .confirmedPrefix());
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

    private static InventoryMenuSwapPlan fiveStepPlan() {
        return InventoryMenuSwapPlanBuilder.swapSequence(
                snapshot(
                        0, SOURCE,
                        1, TARGET,
                        9, TEMPORARY,
                        10, FIRST_UNRELATED,
                        11, SECOND_UNRELATED),
                List.of(
                        new InventoryMenuSwapInstruction(9, 0),
                        new InventoryMenuSwapInstruction(10, 0),
                        new InventoryMenuSwapInstruction(11, 1),
                        new InventoryMenuSwapInstruction(9, 1),
                        new InventoryMenuSwapInstruction(10, 1)));
    }

    private static InventoryMenuSnapshot withState(
            InventoryMenuSnapshot source, int stateId) {
        return new InventoryMenuSnapshot(
                source.containerId(),
                stateId,
                source.selectedHotbar(),
                source.cursor(),
                source.inventorySlots());
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
