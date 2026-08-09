package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;

/** 与原始动作请求绑定的不可变回执。 */
public record DefenseActionReceipt(
        DefenseActionRequest request, DefenseActionOutcome outcome) {
    public DefenseActionReceipt {
        request = Objects.requireNonNull(request, "request");
        outcome = Objects.requireNonNull(outcome, "outcome");
    }
}
