package io.github.greytaiwolf.botplayer.technique.core;

import java.util.Objects;

/** Immutable server-side budget and risk declaration for one technique. */
public record TechniqueDescriptor(
        TechniqueId id,
        TechniqueVersion version,
        TechniqueRiskLevel risk,
        int maximumTicks,
        int maximumPhases,
        int maximumSubmittedChildren,
        int maximumActiveChildren,
        int maximumRecoveryAttempts) {
    public static final int ABSOLUTE_MAXIMUM_TICKS = 600;
    public static final int ABSOLUTE_MAXIMUM_PHASES = 32;
    public static final int ABSOLUTE_MAXIMUM_SUBMITTED_CHILDREN = 128;
    public static final int ABSOLUTE_MAXIMUM_ACTIVE_CHILDREN = 3;
    public static final int ABSOLUTE_MAXIMUM_RECOVERY_ATTEMPTS = 4;

    public TechniqueDescriptor {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(risk, "risk");
        requireRange(maximumTicks, 1, ABSOLUTE_MAXIMUM_TICKS,
                "maximumTicks");
        requireRange(maximumPhases, 1, ABSOLUTE_MAXIMUM_PHASES,
                "maximumPhases");
        requireRange(maximumSubmittedChildren, 0,
                ABSOLUTE_MAXIMUM_SUBMITTED_CHILDREN,
                "maximumSubmittedChildren");
        requireRange(maximumActiveChildren, 1,
                ABSOLUTE_MAXIMUM_ACTIVE_CHILDREN,
                "maximumActiveChildren");
        requireRange(maximumRecoveryAttempts, 0,
                ABSOLUTE_MAXIMUM_RECOVERY_ATTEMPTS,
                "maximumRecoveryAttempts");
    }

    private static void requireRange(int value, int minimum, int maximum,
            String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum + " and " + maximum);
        }
    }
}
