package io.github.greytaiwolf.botplayer.technique.runtime;

/** Global caps in addition to each descriptor's immutable local budget. */
public record TechniqueRuntimeLimits(
        int maximumRegisteredTechniques,
        int maximumActiveRuns,
        int maximumRememberedOutcomes) {
    public static final int ABSOLUTE_MAXIMUM_REGISTERED = 4_096;
    public static final int ABSOLUTE_MAXIMUM_ACTIVE_RUNS = 256;
    public static final int ABSOLUTE_MAXIMUM_REMEMBERED_OUTCOMES = 4_096;

    public TechniqueRuntimeLimits {
        requireRange(maximumRegisteredTechniques, 1,
                ABSOLUTE_MAXIMUM_REGISTERED,
                "maximumRegisteredTechniques");
        requireRange(maximumActiveRuns, 1, ABSOLUTE_MAXIMUM_ACTIVE_RUNS,
                "maximumActiveRuns");
        requireRange(maximumRememberedOutcomes, 1,
                ABSOLUTE_MAXIMUM_REMEMBERED_OUTCOMES,
                "maximumRememberedOutcomes");
    }

    public static TechniqueRuntimeLimits defaults() {
        return new TechniqueRuntimeLimits(512, 64, 512);
    }

    private static void requireRange(int value, int minimum, int maximum,
            String name) {
        if (value < minimum || value > maximum) {
            throw new IllegalArgumentException(name + " must be between "
                    + minimum + " and " + maximum);
        }
    }
}
