package io.github.greytaiwolf.botplayer.action.interaction.menu;

/**
 * 多点击 menu 事务的有界状态机。
 */
public enum InventoryMenuTransactionState {
    PLANNED(false),
    RUNNING(false),
    VERIFYING(false),
    COMMITTED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    InventoryMenuTransactionState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }

    public boolean canTransitionTo(
            InventoryMenuTransactionState target) {
        if (target == null || terminal) {
            return false;
        }
        return switch (this) {
            case PLANNED -> target == RUNNING
                    || target == FAILED
                    || target == CANCELLED;
            case RUNNING -> target == VERIFYING
                    || target == FAILED
                    || target == CANCELLED;
            case VERIFYING -> target == COMMITTED
                    || target == FAILED
                    || target == CANCELLED;
            case COMMITTED, FAILED, CANCELLED -> false;
        };
    }
}
