package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 资源数量的规范、不可变账本。
 *
 * <p>账本只接受正条目，零由省略表示。所有运算都使用有界的精确整数；溢出和负余额
 * 没有“尽量继续”的回退路径。
 */
public record ProductionLedger(Map<ProductionMaterial, Integer> quantities) {
    public static final int MAX_QUANTITY_PER_MATERIAL = 1_000_000;
    private static final ProductionLedger EMPTY = new ProductionLedger(Map.of());

    public ProductionLedger {
        Objects.requireNonNull(quantities, "quantities");
        Map<ProductionMaterial, Integer> canonical = new TreeMap<>();
        for (Map.Entry<ProductionMaterial, Integer> entry
                : quantities.entrySet()) {
            ProductionMaterial material = Objects.requireNonNull(
                    entry.getKey(), "ledger material");
            Integer amount = Objects.requireNonNull(
                    entry.getValue(), "ledger amount");
            requireAmount(amount);
            if (canonical.put(material, amount) != null) {
                throw new IllegalArgumentException(
                        "ledger contains a duplicate material");
            }
        }
        quantities = Map.copyOf(new LinkedHashMap<>(canonical));
    }

    public static ProductionLedger empty() {
        return EMPTY;
    }

    public static ProductionLedger of(
            ProductionMaterial material, int amount) {
        return new ProductionLedger(Map.of(
                Objects.requireNonNull(material, "material"), amount));
    }

    public int quantityOf(ProductionMaterial material) {
        return quantities.getOrDefault(
                Objects.requireNonNull(material, "material"), 0);
    }

    public boolean isEmpty() {
        return quantities.isEmpty();
    }

    public boolean containsAtLeast(ProductionLedger required) {
        Objects.requireNonNull(required, "required");
        for (Map.Entry<ProductionMaterial, Integer> entry
                : required.quantities.entrySet()) {
            if (quantityOf(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    public ProductionLedger plus(ProductionLedger other) {
        Objects.requireNonNull(other, "other");
        Map<ProductionMaterial, Integer> merged = new TreeMap<>(quantities);
        for (Map.Entry<ProductionMaterial, Integer> entry
                : other.quantities.entrySet()) {
            int total;
            try {
                total = Math.addExact(
                        merged.getOrDefault(entry.getKey(), 0),
                        entry.getValue());
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "ledger quantity overflow", exception);
            }
            requireAmount(total);
            merged.put(entry.getKey(), total);
        }
        return new ProductionLedger(merged);
    }

    /**
     * 只有在全部扣款都存在时才返回新账本；不足时不产生部分结果。
     */
    public Optional<ProductionLedger> trySubtract(ProductionLedger required) {
        Objects.requireNonNull(required, "required");
        if (!containsAtLeast(required)) {
            return Optional.empty();
        }
        Map<ProductionMaterial, Integer> remaining = new TreeMap<>(quantities);
        for (Map.Entry<ProductionMaterial, Integer> entry
                : required.quantities.entrySet()) {
            int amount = remaining.get(entry.getKey()) - entry.getValue();
            if (amount == 0) {
                remaining.remove(entry.getKey());
            } else {
                remaining.put(entry.getKey(), amount);
            }
        }
        return Optional.of(new ProductionLedger(remaining));
    }

    public ProductionLedger multipliedBy(int multiplier) {
        if (multiplier < 1) {
            throw new IllegalArgumentException(
                    "ledger multiplier must be positive");
        }
        Map<ProductionMaterial, Integer> scaled = new TreeMap<>();
        for (Map.Entry<ProductionMaterial, Integer> entry
                : quantities.entrySet()) {
            int amount;
            try {
                amount = Math.multiplyExact(entry.getValue(), multiplier);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException(
                        "ledger quantity overflow", exception);
            }
            requireAmount(amount);
            scaled.put(entry.getKey(), amount);
        }
        return new ProductionLedger(scaled);
    }

    private static void requireAmount(int amount) {
        if (amount < 1 || amount > MAX_QUANTITY_PER_MATERIAL) {
            throw new IllegalArgumentException(
                    "ledger quantity must be within 1.."
                            + MAX_QUANTITY_PER_MATERIAL);
        }
    }
}
