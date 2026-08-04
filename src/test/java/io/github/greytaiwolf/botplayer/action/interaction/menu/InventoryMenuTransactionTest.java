package io.github.greytaiwolf.botplayer.action.interaction.menu;

import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InventoryMenuTransactionTest {
    private static final Map<
                    InventoryMenuTransactionState,
                    Set<InventoryMenuTransactionState>>
            LEGAL_TRANSITIONS = Map.of(
                    InventoryMenuTransactionState.PLANNED,
                    EnumSet.of(
                            InventoryMenuTransactionState.RUNNING,
                            InventoryMenuTransactionState.FAILED,
                            InventoryMenuTransactionState.CANCELLED),
                    InventoryMenuTransactionState.RUNNING,
                    EnumSet.of(
                            InventoryMenuTransactionState
                                    .VERIFYING,
                            InventoryMenuTransactionState.FAILED,
                            InventoryMenuTransactionState.CANCELLED),
                    InventoryMenuTransactionState.VERIFYING,
                    EnumSet.of(
                            InventoryMenuTransactionState
                                    .COMMITTED,
                            InventoryMenuTransactionState.FAILED,
                            InventoryMenuTransactionState
                                    .CANCELLED));

    @Test
    void stateTableIsBoundedAndTerminalStatesCannotRevive() {
        for (InventoryMenuTransactionState source :
                InventoryMenuTransactionState.values()) {
            Set<InventoryMenuTransactionState> expected =
                    LEGAL_TRANSITIONS.getOrDefault(
                            source, Set.of());
            for (InventoryMenuTransactionState target :
                    InventoryMenuTransactionState.values()) {
                Assertions.assertEquals(
                        expected.contains(target),
                        source.canTransitionTo(target),
                        () -> "unexpected transition "
                                + source
                                + " -> "
                                + target);
            }
            if (source.terminal()) {
                Assertions.assertTrue(expected.isEmpty());
            }
        }
        Assertions.assertFalse(
                InventoryMenuTransactionState.PLANNED
                        .canTransitionTo(null));
    }

    @Test
    void confirmsOnlyTheStableOrderedStepsBeforeCommit() {
        InventoryMenuSwapPlan plan = plan();
        InventoryMenuTransaction transaction =
                InventoryMenuTransaction.planned(plan);

        Assertions.assertTrue(transaction.nextClick().isEmpty());
        transaction = transaction.start();
        for (int index = 0;
                index < plan.orderedSteps().size();
                index++) {
            Assertions.assertEquals(
                    plan.orderedSteps().get(index),
                    transaction.nextClick().orElseThrow());
            transaction = transaction.confirmNext(
                    plan.orderedSteps().get(index));
        }

        Assertions.assertEquals(
                InventoryMenuTransactionState.VERIFYING,
                transaction.state());
        Assertions.assertEquals(3, transaction.confirmedClicks());
        Assertions.assertTrue(transaction.nextClick().isEmpty());
        transaction = transaction.commit();
        Assertions.assertEquals(
                InventoryMenuTransactionState.COMMITTED,
                transaction.state());
        Assertions.assertTrue(transaction.state().terminal());
    }

    @Test
    void rejectsWrongStepSkippedStageAndTerminalReuse() {
        InventoryMenuSwapPlan plan = plan();
        InventoryMenuTransaction planned =
                InventoryMenuTransaction.planned(plan);
        InventoryMenuTransaction running = planned.start();

        Assertions.assertThrows(
                IllegalArgumentException.class,
                () -> running.confirmNext(
                        plan.orderedSteps().get(1)));
        Assertions.assertThrows(
                IllegalStateException.class, planned::commit);
        InventoryMenuTransaction cancelled =
                running.cancel();
        Assertions.assertThrows(
                IllegalStateException.class, cancelled::start);
        Assertions.assertThrows(
                IllegalStateException.class, cancelled::fail);
    }

    @Test
    void allowsFailClosedTerminationBeforeOrDuringExecution() {
        InventoryMenuTransaction planned =
                InventoryMenuTransaction.planned(plan());
        InventoryMenuTransaction failed = planned.fail();
        InventoryMenuTransaction cancelled =
                planned.start().cancel();

        Assertions.assertEquals(
                InventoryMenuTransactionState.FAILED,
                failed.state());
        Assertions.assertEquals(
                InventoryMenuTransactionState.CANCELLED,
                cancelled.state());
        Assertions.assertTrue(failed.state().terminal());
        Assertions.assertTrue(cancelled.state().terminal());
    }

    @Test
    void transactionCursorSupportsFiveStepGenericPlan() {
        InventoryMenuSwapPlan plan = fiveStepPlan();
        InventoryMenuTransaction transaction =
                InventoryMenuTransaction.planned(plan).start();

        for (InventoryMenuClickStep step :
                plan.orderedSteps()) {
            Assertions.assertEquals(
                    step,
                    transaction.nextClick().orElseThrow());
            transaction = transaction.confirmNext(step);
        }

        Assertions.assertEquals(5, transaction.confirmedClicks());
        Assertions.assertEquals(
                InventoryMenuTransactionState.VERIFYING,
                transaction.state());
        Assertions.assertEquals(
                InventoryMenuTransactionState.COMMITTED,
                transaction.commit().state());
    }

    private static InventoryMenuSwapPlan plan() {
        List<ItemStackFingerprint> slots =
                new ArrayList<>(41);
        for (int index = 0; index < 41; index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(10, item("diamond_helmet", '1'));
        slots.set(39, item("iron_helmet", '2'));
        slots.set(2, item("torch", '3'));
        InventoryMenuSnapshot initial =
                new InventoryMenuSnapshot(
                        0,
                        7,
                        4,
                        ItemStackFingerprint.empty(),
                        slots);
        return InventoryMenuSwapPlanBuilder
                .mainToEquipment(initial, 10, 39, 2);
    }

    private static InventoryMenuSwapPlan fiveStepPlan() {
        List<ItemStackFingerprint> slots =
                new ArrayList<>(41);
        for (int index = 0; index < 41; index++) {
            slots.add(ItemStackFingerprint.empty());
        }
        slots.set(0, item("generic_zero", '3'));
        slots.set(1, item("generic_one", '4'));
        slots.set(9, item("generic_nine", '5'));
        slots.set(10, item("generic_ten", '6'));
        slots.set(11, item("generic_eleven", '7'));
        InventoryMenuSnapshot initial =
                new InventoryMenuSnapshot(
                        0,
                        7,
                        4,
                        ItemStackFingerprint.empty(),
                        slots);
        return InventoryMenuSwapPlanBuilder.swapSequence(
                initial,
                List.of(
                        new InventoryMenuSwapInstruction(9, 0),
                        new InventoryMenuSwapInstruction(10, 0),
                        new InventoryMenuSwapInstruction(11, 1),
                        new InventoryMenuSwapInstruction(9, 1),
                        new InventoryMenuSwapInstruction(10, 1)));
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
