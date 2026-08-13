package io.github.greytaiwolf.botplayer.skill.menu;

/**
 * 失败关闭的菜单事务原因，不包含玩家背包或世界数据。
 */
public enum MenuTransactionFailure {
    TIMEOUT,
    UNEXPECTED_MENU,
    STALE_STATE,
    SNAPSHOT_DRIFT,
    CONSERVATION_BREACH,
    INVALID_PLAN,
    CANCELLED
}
