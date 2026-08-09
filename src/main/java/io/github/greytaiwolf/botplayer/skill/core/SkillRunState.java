package io.github.greytaiwolf.botplayer.skill.core;

import java.util.Objects;

/**
 * 单次技能运行的中央合法状态表。
 */
public enum SkillRunState {
    CREATED,
    PREPARING,
    RUNNING,
    WAITING_ACTION,
    WAITING_NAVIGATION,
    WAITING_MENU,
    WAITING_QUERY,
    WAITING_TIMER,
    PAUSING,
    PAUSED,
    RESUMING,
    RECOVERING,
    VERIFYING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PREEMPTED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, CANCELLED, PREEMPTED -> true;
            case CREATED,
                    PREPARING,
                    RUNNING,
                    WAITING_ACTION,
                    WAITING_NAVIGATION,
                    WAITING_MENU,
                    WAITING_QUERY,
                    WAITING_TIMER,
                    PAUSING,
                    PAUSED,
                    RESUMING,
                    RECOVERING,
                    VERIFYING -> false;
        };
    }

    public boolean canTransitionTo(SkillRunState next) {
        Objects.requireNonNull(next, "next");
        return switch (this) {
            case CREATED ->
                    next == PREPARING
                            || next == CANCELLED
                            || next == FAILED
                            || next == PREEMPTED;
            case PREPARING ->
                    next == RUNNING
                            || isWaiting(next)
                            || next == PAUSING
                            || next == RECOVERING
                            || isTerminalFailure(next);
            case RUNNING ->
                    isWaiting(next)
                            || next == VERIFYING
                            || next == PAUSING
                            || next == RECOVERING
                            || isTerminalFailure(next);
            case WAITING_ACTION,
                    WAITING_NAVIGATION,
                    WAITING_MENU,
                    WAITING_QUERY,
                    WAITING_TIMER ->
                    next == RUNNING
                            || next == VERIFYING
                            || next == PAUSING
                            || next == RECOVERING
                            || isTerminalFailure(next);
            case PAUSING ->
                    next == PAUSED
                            || next == RECOVERING
                            || isTerminalFailure(next);
            case PAUSED ->
                    next == RESUMING
                            || isTerminalFailure(next);
            case RESUMING ->
                    next == PREPARING
                            || next == RUNNING
                            || next == RECOVERING
                            || isTerminalFailure(next);
            case RECOVERING ->
                    next == PREPARING
                            || next == RUNNING
                            || next == PAUSING
                            || isTerminalFailure(next);
            case VERIFYING ->
                    next == PREPARING
                            || isWaiting(next)
                            || next == SUCCEEDED
                            || next == RECOVERING
                            || next == FAILED
                            || next == CANCELLED
                            || next == PREEMPTED;
            case SUCCEEDED, FAILED, CANCELLED, PREEMPTED -> false;
        };
    }

    public void requireTransitionTo(SkillRunState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal skill transition: " + this + " -> " + next);
        }
    }

    private static boolean isWaiting(SkillRunState state) {
        return state == WAITING_ACTION
                || state == WAITING_NAVIGATION
                || state == WAITING_MENU
                || state == WAITING_QUERY
                || state == WAITING_TIMER;
    }

    private static boolean isTerminalFailure(SkillRunState state) {
        return state == FAILED
                || state == CANCELLED
                || state == PREEMPTED;
    }
}
