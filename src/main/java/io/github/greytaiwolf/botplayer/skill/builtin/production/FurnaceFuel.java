package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * P5A 明确允许的燃料及其原版熔炼单位。未知燃料不能被“估算”为可用。
 */
public enum FurnaceFuel {
    COAL(ProductionMaterials.COAL, 8),
    CHARCOAL(ProductionMaterials.CHARCOAL, 8);

    private final ProductionMaterial material;
    private final int smeltUnitsPerItem;

    FurnaceFuel(ProductionMaterial material, int smeltUnitsPerItem) {
        this.material = Objects.requireNonNull(material, "material");
        this.smeltUnitsPerItem = smeltUnitsPerItem;
    }

    public ProductionMaterial material() {
        return material;
    }

    public int smeltUnitsPerItem() {
        return smeltUnitsPerItem;
    }

    public static Optional<FurnaceFuel> resolve(ProductionMaterial material) {
        Objects.requireNonNull(material, "material");
        return Arrays.stream(values())
                .filter(fuel -> fuel.material.equals(material))
                .findFirst();
    }
}
