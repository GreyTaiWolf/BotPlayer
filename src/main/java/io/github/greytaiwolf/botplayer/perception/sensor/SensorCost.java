package io.github.greytaiwolf.botplayer.perception.sensor;

import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * 一次采样可能消耗的分类预算上限，用于编排器预估而不代替实际扣减。
 */
public record SensorCost(Map<BudgetKind, Integer> maximumUnits) {
    public static final int MAXIMUM_UNITS_PER_KIND = 1_000_000;

    public SensorCost {
        Objects.requireNonNull(maximumUnits, "maximumUnits");
        EnumMap<BudgetKind, Integer> copy = new EnumMap<>(BudgetKind.class);
        maximumUnits.forEach((kind, units) -> {
            Objects.requireNonNull(kind, "budget kind");
            Objects.requireNonNull(units, "budget units");
            if (units < 0 || units > MAXIMUM_UNITS_PER_KIND) {
                throw new IllegalArgumentException(
                        "budget units must be between 0 and "
                                + MAXIMUM_UNITS_PER_KIND);
            }
            if (units > 0) {
                copy.put(kind, units);
            }
        });
        maximumUnits = Map.copyOf(copy);
    }

    public static SensorCost none() {
        return new SensorCost(Map.of());
    }

    public static SensorCost of(BudgetKind kind, int maximumUnits) {
        return new SensorCost(Map.of(kind, maximumUnits));
    }
}
