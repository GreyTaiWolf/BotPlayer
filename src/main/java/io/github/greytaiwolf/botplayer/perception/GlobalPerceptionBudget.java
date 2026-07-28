package io.github.greytaiwolf.botplayer.perception;

/**
 * 单 Tick 全服共享工作量上限。
 */
public final class GlobalPerceptionBudget {
    private final int limit;
    private int used;

    public GlobalPerceptionBudget(int limit) {
        if (limit < 1) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
    }

    boolean tryConsume(int units) {
        if (units < 0) {
            throw new IllegalArgumentException("units must not be negative");
        }
        if (units > limit - used) {
            return false;
        }
        used += units;
        return true;
    }

    public int limit() {
        return limit;
    }

    public int used() {
        return used;
    }

    public int remaining() {
        return limit - used;
    }
}
