package io.github.greytaiwolf.botplayer.skill.menu;

/**
 * 通用菜单事务的闭合状态机。
 */
public enum MenuTransactionState {
    OPENING(false),
    SNAPSHOT(false),
    PLANNING(false),
    APPLYING(false),
    ACK(false),
    VERIFYING(false),
    CLOSING(false),
    COMPLETED(true),
    FAILED(true),
    CANCELLED(true);

    private final boolean terminal;

    MenuTransactionState(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean terminal() {
        return terminal;
    }
}
