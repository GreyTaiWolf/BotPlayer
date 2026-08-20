package io.github.greytaiwolf.botplayer.building.site;

import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Fail-closed, data-only assessment derived from a complete {@link ConstructionSiteSurvey}.
 *
 * <p>{@link Status#ACCEPTED_CANDIDATE} means only that all supplied target observations are
 * structurally compatible with the immutable blueprint. It is not a loaded-world fact, a site
 * approval, a lease, an ownership proof, a human confirmation, or authority to execute a
 * Minecraft Action or Technique.
 */
public record ConstructionSiteAssessment(
        ConstructionSiteSurvey survey,
        Status status,
        List<Finding> findings) {
    private static final Comparator<Finding> FINDING_ORDER = Comparator
            .comparing(Finding::offset)
            .thenComparing(Finding::kind);

    /**
     * Rejects caller-supplied assessment values unless they exactly equal the result derived from the survey.
     *
     * <p>This prevents a caller from forging {@link Status#ACCEPTED_CANDIDATE}, omitting a conflict, or
     * changing the priority between a known conflict and an unknown target.
     */
    public ConstructionSiteAssessment {
        survey = Objects.requireNonNull(survey, "survey");
        status = Objects.requireNonNull(status, "status");
        List<Finding> canonicalFindings = canonicalFindings(findings);
        DerivedAssessment expected = derive(survey);
        if (status != expected.status() || !canonicalFindings.equals(expected.findings())) {
            throw new IllegalArgumentException(
                    "construction site assessment must exactly match the derived survey result");
        }
        findings = canonicalFindings;
    }

    /** Derives the only valid assessment for the supplied immutable, exact-cover survey. */
    public static ConstructionSiteAssessment assess(ConstructionSiteSurvey survey) {
        ConstructionSiteSurvey checkedSurvey = Objects.requireNonNull(survey, "survey");
        DerivedAssessment derived = derive(checkedSurvey);
        return new ConstructionSiteAssessment(checkedSurvey, derived.status(), derived.findings());
    }

    /** Assessment outcome ordered from hard known conflict through incomplete evidence to compatible data. */
    public enum Status {
        /** At least one known occupied-target conflict exists; this wins over any unknown target. */
        BLOCKED,
        /** No known conflict exists, but at least one target has no trusted evidence. */
        INCOMPLETE,
        /** Every target is known empty or already exactly matches the expected immutable state. */
        ACCEPTED_CANDIDATE
    }

    /** Why a target prevents a fully compatible candidate assessment. */
    public enum FindingKind {
        /** The survey carries no trusted state evidence for this exact target. */
        UNKNOWN_TARGET(false),
        /** A mismatching occupied target must be preserved under {@link BlueprintReplacePolicy#PRESERVE_EXISTING}. */
        PRESERVE_EXISTING_CONFLICT(true),
        /** A mismatching occupied target would require ownership proof for a temporary replacement. */
        OWNED_TEMPORARY_PROOF_REQUIRED(true),
        /** A mismatching occupied target would require a separate explicit human confirmation. */
        HUMAN_CONFIRMATION_REQUIRED(true);

        private final boolean conflict;

        FindingKind(boolean conflict) {
            this.conflict = conflict;
        }

        /** Returns whether this finding is a known conflict that blocks the candidate. */
        public boolean isConflict() {
            return conflict;
        }
    }

    /**
     * A canonical, pure data finding for one blueprint-relative target.
     *
     * <p>The corresponding expected and observed fingerprints remain in the immutable survey and binding;
     * this finding intentionally carries no capability to replace, lease, or inspect a world.
     */
    public record Finding(BlueprintOffset offset, FindingKind kind) {
        public Finding {
            offset = Objects.requireNonNull(offset, "offset");
            kind = Objects.requireNonNull(kind, "kind");
        }
    }

    private static DerivedAssessment derive(ConstructionSiteSurvey survey) {
        Map<BlueprintOffset, BlueprintCell> expectedCells = new HashMap<>();
        for (BlueprintCell cell : survey.binding().workPlan().blueprint().cells()) {
            expectedCells.put(cell.offset(), cell);
        }

        List<Finding> derivedFindings = new ArrayList<>();
        boolean hasKnownConflict = false;
        boolean hasUnknownTarget = false;
        for (ConstructionSiteSurvey.TargetObservation observation : survey.observations()) {
            BlueprintCell expected = expectedCells.get(observation.offset());
            if (expected == null) {
                throw new IllegalArgumentException(
                        "survey observations must exactly cover every bound blueprint target");
            }
            switch (observation.state()) {
                case UNKNOWN -> {
                    hasUnknownTarget = true;
                    derivedFindings.add(new Finding(observation.offset(),
                            FindingKind.UNKNOWN_TARGET));
                }
                case EMPTY -> {
                    // Empty has no existing block to preserve or replace, so it is structurally compatible.
                }
                case OCCUPIED -> {
                    BlockStateFingerprint observed = observation.observedState().orElseThrow(
                            () -> new IllegalArgumentException(
                                    "occupied target observation must carry a block state"));
                    if (!expected.expectedState().equals(observed)) {
                        hasKnownConflict = true;
                        derivedFindings.add(new Finding(observation.offset(),
                                findingFor(expected.replacePolicy())));
                    }
                }
            }
        }
        List<Finding> canonicalFindings = canonicalFindings(derivedFindings);
        Status derivedStatus = hasKnownConflict
                ? Status.BLOCKED
                : hasUnknownTarget ? Status.INCOMPLETE : Status.ACCEPTED_CANDIDATE;
        return new DerivedAssessment(derivedStatus, canonicalFindings);
    }

    private static FindingKind findingFor(BlueprintReplacePolicy replacePolicy) {
        return switch (Objects.requireNonNull(replacePolicy, "replacePolicy")) {
            case PRESERVE_EXISTING -> FindingKind.PRESERVE_EXISTING_CONFLICT;
            case REPLACE_OWNED_TEMPORARY -> FindingKind.OWNED_TEMPORARY_PROOF_REQUIRED;
            case REQUIRE_HUMAN_CONFIRMATION -> FindingKind.HUMAN_CONFIRMATION_REQUIRED;
        };
    }

    private static List<Finding> canonicalFindings(List<Finding> supplied) {
        Objects.requireNonNull(supplied, "findings");
        List<Finding> canonical = new ArrayList<>(supplied.size());
        for (Finding finding : supplied) {
            canonical.add(Objects.requireNonNull(finding, "assessment finding"));
        }
        canonical.sort(FINDING_ORDER);
        return List.copyOf(canonical);
    }

    private record DerivedAssessment(Status status, List<Finding> findings) {
        private DerivedAssessment {
            status = Objects.requireNonNull(status, "status");
            findings = List.copyOf(Objects.requireNonNull(findings, "findings"));
        }
    }
}
