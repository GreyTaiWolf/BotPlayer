package io.github.greytaiwolf.botplayer.perception;

import java.util.Map;
import java.util.Objects;

public record PerceptionBudgetReport(
        Map<BudgetKind, Integer> limits,
        Map<BudgetKind, Integer> used,
        boolean exhausted) {
    public PerceptionBudgetReport {
        limits = Map.copyOf(Objects.requireNonNull(limits, "limits"));
        used = Map.copyOf(Objects.requireNonNull(used, "used"));
        if (!limits.keySet().equals(used.keySet())) {
            throw new IllegalArgumentException(
                    "budget limits and usage must contain the same kinds");
        }
        for (BudgetKind kind : limits.keySet()) {
            int limit = Objects.requireNonNull(limits.get(kind), "limit");
            int usage = Objects.requireNonNull(used.get(kind), "usage");
            if (limit < 0 || usage < 0 || usage > limit) {
                throw new IllegalArgumentException(
                        "local budget counters are invalid for " + kind);
            }
        }
    }
}
