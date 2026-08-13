package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.Objects;

/**
 * 尚未绑定原版 window id 的单次菜单点击模板。
 */
public record MenuTemplateStep(
        MenuClick click,
        MenuLayout expectedBefore,
        MenuLayout expectedAfter,
        MenuConservationRule conservation) {
    public MenuTemplateStep {
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(conservation, "conservation");
        if (expectedBefore.family() != expectedAfter.family()) {
            throw new IllegalArgumentException(
                    "template click cannot cross menu families");
        }
        click.validateFor(expectedBefore.family());
        if (expectedBefore.equals(expectedAfter)) {
            throw new IllegalArgumentException(
                    "template click must have an observable layout effect");
        }
        if (!conservation.matches(
                expectedBefore.bind(0, 0),
                expectedAfter.bind(0, 1))) {
            throw new IllegalArgumentException(
                    "template layout violates its explicit conservation rule");
        }
    }
}
