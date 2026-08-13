package io.github.greytaiwolf.botplayer.skill.builtin.production;

import java.util.Map;
import java.util.Objects;

/**
 * 一个完整炉次的输入、燃料和可验证产物要求。
 *
 * <p>这不是“把物品塞进炉子”的命令。适配器应利用此对象生成 FURNACE 菜单事务，并只在
 * 结果槽已出现指定产物后报告成功。
 */
public record FurnaceBatchRequirement(
        ProductionMaterial input,
        int inputCount,
        FurnaceFuel fuel,
        int fuelCount,
        ProductionMaterial output,
        int outputCount) {
    public FurnaceBatchRequirement {
        Objects.requireNonNull(input, "input");
        requireCount(inputCount, "inputCount");
        Objects.requireNonNull(fuel, "fuel");
        requireCount(fuelCount, "fuelCount");
        Objects.requireNonNull(output, "output");
        requireCount(outputCount, "outputCount");
        if (input.equals(output) || input.equals(fuel.material())
                || output.equals(fuel.material())) {
            throw new IllegalArgumentException(
                    "furnace input, fuel and output must be distinct");
        }
        if (outputCount > inputCount) {
            throw new IllegalArgumentException(
                    "furnace output cannot exceed its input count");
        }
        long availableBurnUnits = (long) fuelCount
                * fuel.smeltUnitsPerItem();
        if (availableBurnUnits < inputCount) {
            throw new IllegalArgumentException(
                    "furnace fuel cannot cover the requested input count");
        }
    }

    public ProductionDelta playerDelta() {
        return new ProductionDelta(
                new ProductionLedger(Map.of(
                        input, inputCount,
                        fuel.material(), fuelCount)),
                ProductionLedger.of(output, outputCount));
    }

    public FurnaceBatchRequirement multipliedBy(int multiplier) {
        if (multiplier < 1) {
            throw new IllegalArgumentException(
                    "furnace batch multiplier must be positive");
        }
        try {
            return new FurnaceBatchRequirement(
                    input, Math.multiplyExact(inputCount, multiplier),
                    fuel, Math.multiplyExact(fuelCount, multiplier),
                    output, Math.multiplyExact(outputCount, multiplier));
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "furnace batch quantity overflow", exception);
        }
    }

    private static void requireCount(int count, String name) {
        if (count < 1 || count > ProductionLedger.MAX_QUANTITY_PER_MATERIAL) {
            throw new IllegalArgumentException(
                    name + " must be a bounded positive quantity");
        }
    }
}
