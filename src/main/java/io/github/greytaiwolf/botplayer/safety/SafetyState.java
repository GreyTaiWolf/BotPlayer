package io.github.greytaiwolf.botplayer.safety;

public enum SafetyState {
    CLEAR,
    OBSERVING,
    INTERVENING,
    DELEGATED,
    VERIFYING,
    COOLDOWN,
    ESCALATING,
    BLOCKED,
    FAILED
}
