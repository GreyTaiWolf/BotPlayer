package io.github.greytaiwolf.botplayer.skill.menu;

/**
 * 单次通用菜单事务的硬上限。
 */
public record MenuTransactionLimits(int maxClicks, long maxTicks) {
    public static final int HARD_MAX_CLICKS = 64;
    public static final long HARD_MAX_TICKS = 1_200L;

    public MenuTransactionLimits {
        if (maxClicks < 1 || maxClicks > HARD_MAX_CLICKS) {
            throw new IllegalArgumentException(
                    "maxClicks must be in 1..64");
        }
        if (maxTicks < 1L || maxTicks > HARD_MAX_TICKS) {
            throw new IllegalArgumentException(
                    "maxTicks must be in 1..1200");
        }
    }

    public static MenuTransactionLimits defaults() {
        return new MenuTransactionLimits(32, 200L);
    }
}
