package io.github.greytaiwolf.botplayer.action.interaction.menu;

import java.util.Objects;
import java.util.Optional;

/**
 * menu 事务的不可变执行游标；只承认计划中的下一次精确点击。
 */
public record InventoryMenuTransaction(
        InventoryMenuSwapPlan plan,
        InventoryMenuTransactionState state,
        int confirmedClicks) {

    public InventoryMenuTransaction {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        if (confirmedClicks < 0
                || confirmedClicks > plan.orderedSteps().size()) {
            throw new IllegalArgumentException(
                    "confirmedClicks is outside the plan");
        }
        if (state == InventoryMenuTransactionState.PLANNED
                && confirmedClicks != 0) {
            throw new IllegalArgumentException(
                    "planned transaction cannot have confirmed clicks");
        }
        if ((state == InventoryMenuTransactionState.VERIFYING
                        || state
                                == InventoryMenuTransactionState
                                        .COMMITTED)
                && confirmedClicks != plan.orderedSteps().size()) {
            throw new IllegalArgumentException(
                    "verifying or committed transaction requires all clicks");
        }
        if (state == InventoryMenuTransactionState.RUNNING
                && confirmedClicks == plan.orderedSteps().size()) {
            throw new IllegalArgumentException(
                    "running transaction cannot retain all confirmed clicks");
        }
    }

    public static InventoryMenuTransaction planned(
            InventoryMenuSwapPlan plan) {
        return new InventoryMenuTransaction(
                plan, InventoryMenuTransactionState.PLANNED, 0);
    }

    public InventoryMenuTransaction start() {
        requireTransition(InventoryMenuTransactionState.RUNNING);
        return new InventoryMenuTransaction(
                plan, InventoryMenuTransactionState.RUNNING, 0);
    }

    public Optional<InventoryMenuClickStep> nextClick() {
        if (state != InventoryMenuTransactionState.RUNNING) {
            return Optional.empty();
        }
        return Optional.of(
                plan.orderedSteps().get(confirmedClicks));
    }

    public InventoryMenuTransaction confirmNext(
            InventoryMenuClickStep completed) {
        if (state != InventoryMenuTransactionState.RUNNING) {
            throw new IllegalStateException(
                    "only a running transaction can confirm clicks");
        }
        InventoryMenuClickStep expected =
                plan.orderedSteps().get(confirmedClicks);
        if (!expected.equals(
                Objects.requireNonNull(completed, "completed"))) {
            throw new IllegalArgumentException(
                    "completed click does not match the next planned click");
        }
        int updatedClicks = confirmedClicks + 1;
        InventoryMenuTransactionState updatedState =
                updatedClicks == plan.orderedSteps().size()
                        ? InventoryMenuTransactionState.VERIFYING
                        : InventoryMenuTransactionState.RUNNING;
        return new InventoryMenuTransaction(
                plan, updatedState, updatedClicks);
    }

    public InventoryMenuTransaction commit() {
        requireTransition(InventoryMenuTransactionState.COMMITTED);
        return new InventoryMenuTransaction(
                plan,
                InventoryMenuTransactionState.COMMITTED,
                confirmedClicks);
    }

    public InventoryMenuTransaction fail() {
        requireTransition(InventoryMenuTransactionState.FAILED);
        return new InventoryMenuTransaction(
                plan,
                InventoryMenuTransactionState.FAILED,
                confirmedClicks);
    }

    public InventoryMenuTransaction cancel() {
        requireTransition(InventoryMenuTransactionState.CANCELLED);
        return new InventoryMenuTransaction(
                plan,
                InventoryMenuTransactionState.CANCELLED,
                confirmedClicks);
    }

    private void requireTransition(
            InventoryMenuTransactionState target) {
        if (!state.canTransitionTo(target)) {
            throw new IllegalStateException(
                    "illegal menu transaction state transition");
        }
    }
}
