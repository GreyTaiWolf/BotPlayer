package io.github.greytaiwolf.botplayer.worldmodel;

/**
 * 事实的失效规则。0 TTL 表示只由明确 scope 变化或新观察失效。
 */
public record InvalidationRule(long ttlTicks, boolean staleOnScopeChange) {
    public static final long MAX_TTL_TICKS = 20L * 60L * 60L * 24L * 30L;

    public InvalidationRule {
        if (ttlTicks < 0 || ttlTicks > MAX_TTL_TICKS) {
            throw new IllegalArgumentException(
                    "ttlTicks must be between 0 and " + MAX_TTL_TICKS);
        }
    }
}
