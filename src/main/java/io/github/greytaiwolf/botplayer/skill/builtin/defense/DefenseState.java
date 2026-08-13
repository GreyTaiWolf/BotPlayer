package io.github.greytaiwolf.botplayer.skill.builtin.defense;

/** 有限自卫的显式状态。 */
public enum DefenseState {
    READY,
    ATTACK_IN_FLIGHT,
    RETREAT_IN_FLIGHT,
    COMPLETED,
    REJECTED,
    EXHAUSTED,
    PREEMPTED;

    public boolean terminal() {
        return switch (this) {
            case COMPLETED, REJECTED, EXHAUSTED, PREEMPTED -> true;
            case READY, ATTACK_IN_FLIGHT, RETREAT_IN_FLIGHT -> false;
        };
    }
}
