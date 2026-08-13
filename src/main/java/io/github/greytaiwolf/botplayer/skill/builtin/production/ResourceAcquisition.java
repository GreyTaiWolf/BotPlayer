package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Objects;

/**
 * 对真实采集/挖掘技能的结果约束，而非直接给予物品。
 */
public record ResourceAcquisition(
        AcquisitionMethod method, ProductionLedger expectedGain)
        implements ProductionOperation {
    public ResourceAcquisition {
        Objects.requireNonNull(method, "method");
        Objects.requireNonNull(expectedGain, "expectedGain");
        if (expectedGain.isEmpty()) {
            throw new IllegalArgumentException(
                    "resource acquisition must require an observable gain");
        }
    }

    @Override
    public ProductionOperationKind kind() {
        return ProductionOperationKind.ACQUIRE_RESOURCES;
    }

    public ProductionDelta expectedDelta() {
        return new ProductionDelta(ProductionLedger.empty(), expectedGain);
    }
}
