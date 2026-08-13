package io.github.greytaiwolf.botplayer.skill.menu;

import java.util.Objects;

/**
 * 计划中的一次精确点击及其完整前后布局证据。
 */
public record MenuClickStep(
        MenuClick click,
        MenuSnapshot expectedBefore,
        MenuSnapshot expectedAfter,
        MenuConservationRule conservation) {

    public MenuClickStep {
        Objects.requireNonNull(click, "click");
        Objects.requireNonNull(expectedBefore, "expectedBefore");
        Objects.requireNonNull(expectedAfter, "expectedAfter");
        Objects.requireNonNull(conservation, "conservation");
        if (!expectedBefore.sameMenu(expectedAfter)) {
            throw new IllegalArgumentException(
                    "click cannot cross menu family or container id");
        }
        click.validateFor(expectedBefore.family());
        if (expectedBefore.layoutEqualsIgnoringState(expectedAfter)) {
            throw new IllegalArgumentException(
                    "click must have an observable cursor or slot effect");
        }
        if (!conservation.matches(expectedBefore, expectedAfter)) {
            throw new IllegalArgumentException(
                    "click layout violates its explicit conservation rule");
        }
    }
}
