package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * 一项生产步骤对玩家账本的精确扣款与入账。
 */
public record ProductionDelta(
        ProductionLedger debits, ProductionLedger credits) {
    public ProductionDelta {
        Objects.requireNonNull(debits, "debits");
        Objects.requireNonNull(credits, "credits");
        Set<ProductionMaterial> overlap = new HashSet<>(
                debits.quantities().keySet());
        overlap.retainAll(credits.quantities().keySet());
        if (!overlap.isEmpty()) {
            throw new IllegalArgumentException(
                    "a production delta must not debit and credit the same material");
        }
        if (debits.isEmpty() && credits.isEmpty()) {
            throw new IllegalArgumentException(
                    "a production delta must have an observable effect");
        }
    }

    public Optional<ProductionLedger> applyTo(ProductionLedger before) {
        Objects.requireNonNull(before, "before");
        return before.trySubtract(debits).map(remaining ->
                remaining.plus(credits));
    }

    public ProductionDelta multipliedBy(int multiplier) {
        return new ProductionDelta(
                debits.multipliedBy(multiplier),
                credits.multipliedBy(multiplier));
    }
}
