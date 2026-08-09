package io.github.greytaiwolf.botplayer.skill.builtin.production;

/**
 * 菜单/账本证据不足时的封闭拒绝原因。
 */
public enum ProductionPreconditionRejection {
    UNEXPECTED_MENU_OPEN,
    MENU_NOT_OPEN,
    MENU_FAMILY_MISMATCH,
    CURSOR_NOT_EMPTY,
    PLAYER_LEDGER_INSUFFICIENT,
    CHEST_LEDGER_MISSING,
    CHEST_LEDGER_INSUFFICIENT
}
