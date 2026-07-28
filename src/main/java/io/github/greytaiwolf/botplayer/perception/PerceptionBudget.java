package io.github.greytaiwolf.botplayer.perception;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 单 bot 的分类读取预算，同时服从全服工作量上限。
 */
public final class PerceptionBudget {
    private final GlobalPerceptionBudget global;
    private final EnumMap<BudgetKind, Integer> limits =
            new EnumMap<>(BudgetKind.class);
    private final EnumMap<BudgetKind, Integer> used =
            new EnumMap<>(BudgetKind.class);
    private boolean exhausted;

    public PerceptionBudget(
            GlobalPerceptionBudget global, Map<BudgetKind, Integer> limits) {
        this.global = Objects.requireNonNull(global, "global");
        Objects.requireNonNull(limits, "limits");
        for (BudgetKind kind : BudgetKind.values()) {
            int limit = Objects.requireNonNull(
                    limits.get(kind), "missing limit for " + kind);
            if (limit < 0) {
                throw new IllegalArgumentException("budget limits must not be negative");
            }
            this.limits.put(kind, limit);
            used.put(kind, 0);
        }
    }

    public boolean tryConsume(BudgetKind kind) {
        return tryConsume(kind, 1);
    }

    public boolean tryConsume(BudgetKind kind, int units) {
        Objects.requireNonNull(kind, "kind");
        if (units < 0) {
            throw new IllegalArgumentException("units must not be negative");
        }
        int current = used.get(kind);
        if (units > limits.get(kind) - current) {
            exhausted = true;
            return false;
        }
        if (!global.tryConsume(units)) {
            exhausted = true;
            return false;
        }
        used.put(kind, current + units);
        return true;
    }

    public PerceptionBudgetReport report() {
        return new PerceptionBudgetReport(limits, used, exhausted);
    }

    public int remaining(BudgetKind kind) {
        Objects.requireNonNull(kind, "kind");
        return Math.min(
                limits.get(kind) - used.get(kind),
                global.remaining());
    }
}
