package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable package-local view of raw observations from one complete exact site survey.
 *
 * <p>This projection only pairs one already-bound work-package target manifest with the raw
 * observations from the same immutable candidate-site binding. It does not assess compatibility
 * or freshness; in particular, an {@link ConstructionSiteSurvey.TargetState#UNKNOWN} value remains
 * unknown rather than becoming an empty, accepted, ready, or placeable target. It does not retain
 * a lease, material observation, allocation, reservation, reachability, facing, click surface,
 * placement candidate, Action, Technique, Skill, or world-write authority.
 */
public final class ConstructionWorkPackageSiteSurveyProjection {
    private final ConstructionWorkPackageSiteTargetProjection targetManifest;
    private final ConstructionSiteSurvey survey;
    private final List<TargetObservation> observations;

    private ConstructionWorkPackageSiteSurveyProjection(
            ConstructionWorkPackageSiteTargetProjection targetManifest,
            ConstructionSiteSurvey survey,
            List<TargetObservation> observations) {
        this.targetManifest = Objects.requireNonNull(targetManifest, "targetManifest");
        this.survey = Objects.requireNonNull(survey, "survey");
        requireSameBinding(targetManifest, survey);
        this.observations = canonicalAndValidate(targetManifest, survey, observations);
    }

    /**
     * Pairs every actual target in one exact package with its raw observation from one full survey.
     */
    public static ConstructionWorkPackageSiteSurveyProjection project(
            ConstructionWorkPackageSiteTargetProjection targetManifest,
            ConstructionSiteSurvey survey) {
        ConstructionWorkPackageSiteTargetProjection checkedManifest = Objects.requireNonNull(
                targetManifest, "targetManifest");
        ConstructionSiteSurvey checkedSurvey = Objects.requireNonNull(survey, "survey");
        requireSameBinding(checkedManifest, checkedSurvey);

        Map<BlueprintOffset, ConstructionSiteSurvey.TargetObservation> observationsByOffset =
                observationsByOffset(checkedSurvey);
        List<TargetObservation> pairs = new ArrayList<>(checkedManifest.targetCount());
        for (ConstructionWorkPackageSiteTargetProjection.Target target : checkedManifest.targets()) {
            BlockCoordinates expectedPosition = checkedManifest.binding().targetPosition(
                    checkedManifest.workPackageKey(), target.cell().offset());
            if (!target.targetPosition().equals(expectedPosition)) {
                throw new IllegalArgumentException(
                        "work package target must exactly match the bound package coordinate");
            }
            ConstructionSiteSurvey.TargetObservation observation = observationsByOffset.remove(
                    target.cell().offset());
            if (observation == null) {
                throw new IllegalArgumentException(
                        "survey must contain every bound work package target observation");
            }
            pairs.add(new TargetObservation(target, observation));
        }
        return new ConstructionWorkPackageSiteSurveyProjection(checkedManifest, checkedSurvey, pairs);
    }

    /** Returns the exact immutable manifest that supplied canonical package target order. */
    public ConstructionWorkPackageSiteTargetProjection targetManifest() {
        return targetManifest;
    }

    /** Returns the complete immutable survey that supplied raw target evidence. */
    public ConstructionSiteSurvey survey() {
        return survey;
    }

    /**
     * Returns the survey's raw observation tick without turning it into a freshness or lease proof.
     */
    public long observedTick() {
        return survey.observedTick();
    }

    /** Returns one raw observation for each package target in canonical package-cell order. */
    public List<TargetObservation> observations() {
        return observations;
    }

    /** Returns the exact bounded number of paired package targets, never a progress or ready count. */
    public int observationCount() {
        return observations.size();
    }

    private static void requireSameBinding(
            ConstructionWorkPackageSiteTargetProjection targetManifest,
            ConstructionSiteSurvey survey) {
        if (!targetManifest.binding().equals(survey.binding())) {
            throw new IllegalArgumentException(
                    "work package target manifest and survey must use the exact same candidate binding");
        }
    }

    private static List<TargetObservation> canonicalAndValidate(
            ConstructionWorkPackageSiteTargetProjection targetManifest,
            ConstructionSiteSurvey survey,
            List<TargetObservation> supplied) {
        Objects.requireNonNull(supplied, "observations");
        if (supplied.size() != targetManifest.targetCount()) {
            throw new IllegalArgumentException(
                    "work package survey projection must exactly cover every package target");
        }

        Map<BlueprintOffset, ConstructionSiteSurvey.TargetObservation> observationsByOffset =
                observationsByOffset(survey);
        List<TargetObservation> canonical = new ArrayList<>(supplied.size());
        for (int index = 0; index < targetManifest.targetCount(); index++) {
            ConstructionWorkPackageSiteTargetProjection.Target expectedTarget = targetManifest
                    .targets()
                    .get(index);
            BlockCoordinates expectedPosition = targetManifest.binding().targetPosition(
                    targetManifest.workPackageKey(), expectedTarget.cell().offset());
            TargetObservation suppliedObservation = Objects.requireNonNull(supplied.get(index),
                    "work package survey target observation");
            if (!suppliedObservation.target().equals(expectedTarget)
                    || !suppliedObservation.target().targetPosition().equals(expectedPosition)) {
                throw new IllegalArgumentException(
                        "work package survey target must exactly match the bound package target");
            }
            ConstructionSiteSurvey.TargetObservation expectedObservation =
                    observationsByOffset.remove(expectedTarget.cell().offset());
            if (!suppliedObservation.observation().equals(expectedObservation)) {
                throw new IllegalArgumentException(
                        "work package survey observation must exactly match the complete survey");
            }
            canonical.add(suppliedObservation);
        }
        return List.copyOf(canonical);
    }

    private static Map<BlueprintOffset, ConstructionSiteSurvey.TargetObservation> observationsByOffset(
            ConstructionSiteSurvey survey) {
        Map<BlueprintOffset, ConstructionSiteSurvey.TargetObservation> observationsByOffset =
                new HashMap<>(survey.observations().size());
        for (ConstructionSiteSurvey.TargetObservation observation : survey.observations()) {
            ConstructionSiteSurvey.TargetObservation previous = observationsByOffset.putIfAbsent(
                    observation.offset(), observation);
            if (previous != null) {
                throw new IllegalArgumentException(
                        "survey observations must have unique bound target offsets");
            }
        }
        return observationsByOffset;
    }

    /** One exact package target paired with its raw, unassessed full-survey observation. */
    public record TargetObservation(
            ConstructionWorkPackageSiteTargetProjection.Target target,
            ConstructionSiteSurvey.TargetObservation observation) {
        public TargetObservation {
            target = Objects.requireNonNull(target, "target");
            observation = Objects.requireNonNull(observation, "observation");
        }
    }
}
