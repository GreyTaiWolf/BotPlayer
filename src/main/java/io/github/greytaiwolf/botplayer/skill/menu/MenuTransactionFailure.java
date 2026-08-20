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
    /**
     * 原版 {@code clicked()} 或紧随其后的变更广播抛出。调用方不得把任何可能已经
     * 发生的原版变更当作已确认 click；只能按终态失败路径关闭 menu。
     */
    CLICK_DISPATCH_FAILED,
    CANCELLED
}
