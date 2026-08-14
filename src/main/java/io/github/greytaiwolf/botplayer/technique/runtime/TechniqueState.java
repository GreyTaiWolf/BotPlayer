package io.github.greytaiwolf.botplayer.technique.runtime;

import java.util.Objects;

/** Finite lifecycle of one non-persistent body technique. */
public enum TechniqueState {
    CREATED,
    PREPARING,
    RUNNING,
    WAITING_CHILDREN,
    VERIFYING,
    RECOVERING,
    CANCELLING,
    PREEMPTING,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    PREEMPTED;

    public boolean isTerminal() {
        return switch (this) {
            case SUCCEEDED, FAILED, CANCELLED, PREEMPTED -> true;
            case CREATED, PREPARING, RUNNING, WAITING_CHILDREN, VERIFYING,
                    RECOVERING, CANCELLING, PREEMPTING -> false;
        };
    }

    public boolean canTransitionTo(TechniqueState next) {
        Objects.requireNonNull(next, "next");
        return switch (this) {
            case CREATED -> next == PREPARING || next == FAILED
                    || next == CANCELLED || next == PREEMPTED;
            case PREPARING -> next == RUNNING || next == WAITING_CHILDREN
                    || next == VERIFYING || next == RECOVERING
                    || next == CANCELLING || next == PREEMPTING
                    || next == SUCCEEDED || next == FAILED;
            case RUNNING -> next == WAITING_CHILDREN || next == VERIFYING
                    || next == RECOVERING || next == CANCELLING
                    || next == PREEMPTING || next == SUCCEEDED
                    || next == FAILED;
            case WAITING_CHILDREN -> next == RUNNING || next == CANCELLING
                    || next == PREEMPTING || next == FAILED;
            case VERIFYING -> next == RUNNING || next == RECOVERING
                    || next == CANCELLING || next == PREEMPTING
                    || next == SUCCEEDED || next == FAILED;
            case RECOVERING -> next == PREPARING || next == RUNNING
                    || next == CANCELLING || next == PREEMPTING
                    || next == FAILED;
            /* A later L0 safety event may escalate an ordinary cancellation. */
            case CANCELLING -> next == CANCELLED || next == PREEMPTING
                    || next == FAILED;
            case PREEMPTING -> next == PREEMPTED || next == FAILED;
            case SUCCEEDED, FAILED, CANCELLED, PREEMPTED -> false;
        };
    }

    public void requireTransitionTo(TechniqueState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException("illegal technique transition: "
                    + this + " -> " + next);
        }
    }
}
