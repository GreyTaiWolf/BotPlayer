package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Immutable caller-supplied observations for every exact target of one candidate construction site.
 *
 * <p>This is a pure DTO boundary. It neither reads nor loads a Minecraft world: {@link TargetState#UNKNOWN}
 * means that this survey lacks trusted evidence for that exact target, not that any particular chunk is
 * unloaded. It does not grant a site lease, replacement permission, Action, Technique, or placement
 * authority.
 */
public record ConstructionSiteSurvey(
        ConstructionSiteBinding binding,
        long observedTick,
        List<TargetObservation> observations) {
    private static final Comparator<TargetObservation> OBSERVATION_ORDER =
            Comparator.comparing(TargetObservation::offset);

    public ConstructionSiteSurvey {
        binding = Objects.requireNonNull(binding, "binding");
        if (observedTick < 0L) {
            throw new IllegalArgumentException("survey observed tick must not be negative");
        }
        observations = canonicalAndValidate(binding, observations);
    }

    /**
     * Returns the exact candidate-world coordinate for a surveyed blueprint offset.
     *
     * <p>This only delegates to the immutable binding's checked coordinate translation. It performs no
     * world lookup.
     */
    public BlockCoordinates targetPosition(BlueprintOffset offset) {
        return binding.targetPosition(offset);
    }

    /** Derives a fail-closed, data-only assessment from this complete immutable survey. */
    public ConstructionSiteAssessment assess() {
        return ConstructionSiteAssessment.assess(this);
    }

    /** The only trusted-evidence categories this DTO can carry for one blueprint target. */
    public enum TargetState {
        /** No trusted target-state evidence is present in this survey. */
        UNKNOWN,
        /** Trusted evidence says that the exact target is empty. */
        EMPTY,
        /** Trusted evidence carries a concrete immutable block-state fingerprint. */
        OCCUPIED
    }

    /**
     * One target observation keyed by a blueprint-relative offset.
     *
     * <p>{@code UNKNOWN} and {@code EMPTY} must not carry a block fingerprint; {@code OCCUPIED} must carry
     * exactly one. The coordinate is intentionally derived from the enclosing exact binding instead of
     * being caller-supplied.
     */
    public record TargetObservation(
            BlueprintOffset offset,
            TargetState state,
            Optional<BlockStateFingerprint> observedState) {
        public TargetObservation {
            offset = Objects.requireNonNull(offset, "offset");
            state = Objects.requireNonNull(state, "state");
            observedState = Objects.requireNonNull(observedState, "observedState");
            switch (state) {
                case UNKNOWN, EMPTY -> {
                    if (observedState.isPresent()) {
                        throw new IllegalArgumentException(
                                "unknown or empty target observation must not carry a block state");
                    }
                }
                case OCCUPIED -> {
                    if (observedState.isEmpty()) {
                        throw new IllegalArgumentException(
                                "occupied target observation must carry a block state");
                    }
                }
            }
        }

        /** Creates an observation that deliberately has no trusted evidence for this target. */
        public static TargetObservation unknown(BlueprintOffset offset) {
            return new TargetObservation(offset, TargetState.UNKNOWN, Optional.empty());
        }

        /** Creates an observation with trusted empty-target evidence. */
        public static TargetObservation empty(BlueprintOffset offset) {
            return new TargetObservation(offset, TargetState.EMPTY, Optional.empty());
        }

        /** Creates an observation with a concrete immutable occupied-target fingerprint. */
        public static TargetObservation occupied(
                BlueprintOffset offset, BlockStateFingerprint observedState) {
            return new TargetObservation(offset, TargetState.OCCUPIED,
                    Optional.of(Objects.requireNonNull(observedState, "observedState")));
        }
    }

    private static List<TargetObservation> canonicalAndValidate(
            ConstructionSiteBinding binding, List<TargetObservation> supplied) {
        Objects.requireNonNull(supplied, "observations");
        List<BlueprintCell> cells = binding.workPlan().blueprint().cells();
        if (supplied.size() != cells.size()) {
            throw new IllegalArgumentException(
                    "survey observations must exactly cover every bound blueprint target");
        }

        List<TargetObservation> canonical = new ArrayList<>(supplied.size());
        for (TargetObservation observation : supplied) {
            canonical.add(Objects.requireNonNull(observation, "survey observation"));
        }
        canonical.sort(OBSERVATION_ORDER);

        Set<BlueprintOffset> remainingOffsets = new HashSet<>(cells.size());
        for (BlueprintCell cell : cells) {
            remainingOffsets.add(cell.offset());
        }
        for (TargetObservation observation : canonical) {
            if (!remainingOffsets.remove(observation.offset())) {
                throw new IllegalArgumentException(
                        "survey observations must exactly cover every bound blueprint target");
            }
            // Exercise the exact binding fence rather than trusting an independently supplied position.
            binding.targetPosition(observation.offset());
        }
        if (!remainingOffsets.isEmpty()) {
            throw new IllegalArgumentException(
                    "survey observations must exactly cover every bound blueprint target");
        }
        return List.copyOf(canonical);
    }
}
