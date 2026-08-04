package io.github.greytaiwolf.botplayer.skill.runtime;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * 不读取世界活对象的确定性生存决策。
 */
public final class SurvivalSkillPolicy {
    private SurvivalSkillPolicy() {}

    public static OptionalInt chooseFood(
            List<FoodOption> options) {
        Objects.requireNonNull(options, "options");
        return options.stream()
                .filter(FoodOption::usable)
                .filter(option -> !option.harmfulEffect())
                .sorted(Comparator
                        .comparingInt(FoodOption::nutrition)
                        .reversed()
                        .thenComparing(
                                Comparator.comparingDouble(
                                                FoodOption::saturation)
                                        .reversed())
                        .thenComparing(
                                Comparator.comparing(
                                                FoodOption::alreadyInHand)
                                        .reversed())
                        .thenComparingInt(FoodOption::inventorySlot))
                .mapToInt(FoodOption::inventorySlot)
                .findFirst();
    }

    public record FoodOption(
            int inventorySlot,
            int nutrition,
            float saturation,
            boolean harmfulEffect,
            boolean usable,
            boolean alreadyInHand) {
        public FoodOption {
            if (inventorySlot < 0 || inventorySlot > 40) {
                throw new IllegalArgumentException(
                        "inventorySlot must be between 0 and 40");
            }
            if (nutrition < 0
                    || !Float.isFinite(saturation)
                    || saturation < 0.0F) {
                throw new IllegalArgumentException(
                        "food values must be finite and non-negative");
            }
        }
    }

}
