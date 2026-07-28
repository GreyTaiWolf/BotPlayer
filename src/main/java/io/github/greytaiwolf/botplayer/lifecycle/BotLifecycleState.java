package io.github.greytaiwolf.botplayer.lifecycle;

public enum BotLifecycleState {
    SPAWNING,
    ACTIVE,
    DEAD,
    RESPAWNING,
    DESPAWNING;

    public boolean canTransitionTo(BotLifecycleState next) {
        return switch (this) {
            case SPAWNING ->
                    next == ACTIVE || next == DEAD || next == DESPAWNING;
            case ACTIVE -> next == DEAD || next == DESPAWNING;
            case DEAD -> next == RESPAWNING || next == DESPAWNING;
            case RESPAWNING ->
                    next == ACTIVE || next == DEAD || next == DESPAWNING;
            case DESPAWNING -> false;
        };
    }

    public void requireTransitionTo(BotLifecycleState next) {
        if (!canTransitionTo(next)) {
            throw new IllegalStateException(
                    "Illegal bot lifecycle transition: " + this + " -> " + next);
        }
    }
}
