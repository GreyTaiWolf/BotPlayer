package io.github.greytaiwolf.botplayer.safety;

public enum HazardSeverity {
    ADVISORY(0),
    WARNING(1),
    URGENT(2),
    EMERGENCY(3);

    private final int rank;

    HazardSeverity(int rank) {
        this.rank = rank;
    }

    public int rank() {
        return rank;
    }
}
