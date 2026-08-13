package io.github.greytaiwolf.botplayer.skill.builtin.defense;

import java.util.Objects;
import java.util.Optional;

/**
 * 供真实动作适配层读取的当前状态快照。
 *
 * <p>只有 in-flight 状态携带动作；其他状态都不能借这个对象发出额外副作用。
 */
public record DefenseDecision(
        DefenseState state,
        Optional<DefenseActionRequest> action,
        DefenseReason reason,
        int attackAttemptsRemaining,
        int retreatAttemptsRemaining) {
    public DefenseDecision {
        state = Objects.requireNonNull(state, "state");
        action = Objects.requireNonNull(action, "action");
        reason = Objects.requireNonNull(reason, "reason");
        if (attackAttemptsRemaining < 0 || retreatAttemptsRemaining < 0) {
            throw new IllegalArgumentException("remaining budgets must be non-negative");
        }
        boolean actionRequired = state == DefenseState.ATTACK_IN_FLIGHT
                || state == DefenseState.RETREAT_IN_FLIGHT;
        if (action.isPresent() != actionRequired) {
            throw new IllegalArgumentException(
                    "only in-flight states may carry an action");
        }
        if (action.isPresent()) {
            validateAction(state, action.orElseThrow());
        }
    }

    private static void validateAction(
            DefenseState state, DefenseActionRequest action) {
        if (state == DefenseState.ATTACK_IN_FLIGHT
                && action.kind() != DefenseActionKind.MELEE_ATTACK) {
            throw new IllegalArgumentException(
                    "attack state requires a melee action");
        }
        if (state == DefenseState.RETREAT_IN_FLIGHT
                && action.kind() != DefenseActionKind.RETREAT) {
            throw new IllegalArgumentException(
                    "retreat state requires a retreat action");
        }
    }
}
