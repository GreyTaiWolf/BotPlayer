package io.github.greytaiwolf.botplayer.building.site;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionSiteSurveyTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a01");
    private static final UUID SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000a02");
    private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId DIRT = new ResourceId("minecraft:dirt");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE,
            Map.of());
    private static final BlockStateFingerprint DIRT_STATE = new BlockStateFingerprint(DIRT,
            Map.of());

    @Test
    void canonicalizesAndDefensivelyCopiesExactTargetObservations() {
        ConstructionSiteBinding binding = binding(policyBlueprint());
        List<ConstructionSiteSurvey.TargetObservation> supplied = new ArrayList<>(List.of(
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                        STONE_STATE),
                ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                ConstructionSiteSurvey.TargetObservation.unknown(new BlueprintOffset(-1, 0, 0))));

        ConstructionSiteSurvey survey = new ConstructionSiteSurvey(binding, 42L, supplied);
        supplied.clear();

        assertEquals(42L, survey.observedTick());
        assertEquals(List.of(
                        new BlueprintOffset(-1, 0, 0),
                        new BlueprintOffset(0, 0, 0),
                        new BlueprintOffset(1, 0, 0)),
                survey.observations().stream()
                        .map(ConstructionSiteSurvey.TargetObservation::offset)
                        .toList());
        assertEquals(new BlockCoordinates(11, 64, -7), survey.targetPosition(
                new BlueprintOffset(1, 0, 0)));
        assertThrows(UnsupportedOperationException.class,
                () -> survey.observations().add(
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(2, 0, 0))));
    }

    @Test
    void rejectsIncompleteDuplicateOrForeignTargetCoverageAndInvalidTick() {
        ConstructionSiteBinding binding = binding(policyBlueprint());
        List<ConstructionSiteSurvey.TargetObservation> complete = observationsFor(
                binding.workPlan().blueprint(), ConstructionSiteSurvey.TargetState.EMPTY);

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey(binding, -1L, complete));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey(binding, 1L, complete.subList(0, 2)));

        List<ConstructionSiteSurvey.TargetObservation> duplicate = new ArrayList<>(complete);
        duplicate.set(2, ConstructionSiteSurvey.TargetObservation.empty(
                complete.get(0).offset()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey(binding, 1L, duplicate));

        List<ConstructionSiteSurvey.TargetObservation> foreign = new ArrayList<>(complete);
        foreign.set(2, ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(2, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey(binding, 1L, foreign));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey(binding, 1L, List.of(
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(-1, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(2, 0, 0)))));
    }

    @Test
    void rejectsUnknownEmptyAndOccupiedStatePairingViolations() {
        BlueprintOffset offset = new BlueprintOffset(0, 0, 0);

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey.TargetObservation(offset,
                        ConstructionSiteSurvey.TargetState.UNKNOWN, Optional.of(STONE_STATE)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey.TargetObservation(offset,
                        ConstructionSiteSurvey.TargetState.EMPTY, Optional.of(STONE_STATE)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteSurvey.TargetObservation(offset,
                        ConstructionSiteSurvey.TargetState.OCCUPIED, Optional.empty()));
        assertThrows(NullPointerException.class,
                () -> new ConstructionSiteSurvey.TargetObservation(offset,
                        ConstructionSiteSurvey.TargetState.OCCUPIED, null));
    }

    @Test
    void mapsEveryReplacementPolicyToItsFailClosedFinding() {
        ConstructionSiteBinding binding = binding(policyBlueprint());
        ConstructionSiteSurvey survey = new ConstructionSiteSurvey(binding, 5L, List.of(
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                        DIRT_STATE),
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(-1, 0, 0),
                        DIRT_STATE),
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(0, 0, 0),
                        DIRT_STATE)));

        ConstructionSiteAssessment assessment = ConstructionSiteAssessment.assess(survey);

        assertEquals(ConstructionSiteAssessment.Status.BLOCKED, assessment.status());
        assertEquals(List.of(
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(-1, 0, 0),
                                ConstructionSiteAssessment.FindingKind.PRESERVE_EXISTING_CONFLICT),
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(0, 0, 0),
                                ConstructionSiteAssessment.FindingKind.OWNED_TEMPORARY_PROOF_REQUIRED),
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(1, 0, 0),
                                ConstructionSiteAssessment.FindingKind.HUMAN_CONFIRMATION_REQUIRED)),
                assessment.findings());
        assertTrue(assessment.findings().stream().allMatch(finding -> finding.kind().isConflict()));
    }

    @Test
    void appliesBlockedThenIncompleteThenAcceptedCandidateStatusPriority() {
        ConstructionSiteBinding binding = binding(policyBlueprint());

        ConstructionSiteAssessment incomplete = ConstructionSiteAssessment.assess(
                new ConstructionSiteSurvey(binding, 6L, List.of(
                        ConstructionSiteSurvey.TargetObservation.unknown(new BlueprintOffset(-1, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                                STONE_STATE))));
        assertEquals(ConstructionSiteAssessment.Status.INCOMPLETE, incomplete.status());
        assertEquals(List.of(new ConstructionSiteAssessment.Finding(new BlueprintOffset(-1, 0, 0),
                        ConstructionSiteAssessment.FindingKind.UNKNOWN_TARGET)),
                incomplete.findings());

        ConstructionSiteAssessment blockedOverUnknown = ConstructionSiteAssessment.assess(
                new ConstructionSiteSurvey(binding, 7L, List.of(
                        ConstructionSiteSurvey.TargetObservation.unknown(new BlueprintOffset(-1, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                                DIRT_STATE))));
        assertEquals(ConstructionSiteAssessment.Status.BLOCKED, blockedOverUnknown.status());
        assertEquals(List.of(
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(-1, 0, 0),
                                ConstructionSiteAssessment.FindingKind.UNKNOWN_TARGET),
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(1, 0, 0),
                                ConstructionSiteAssessment.FindingKind.HUMAN_CONFIRMATION_REQUIRED)),
                blockedOverUnknown.findings());

        ConstructionSiteAssessment accepted = ConstructionSiteAssessment.assess(
                new ConstructionSiteSurvey(binding, 8L, List.of(
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(-1, 0, 0)),
                        ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(0, 0, 0),
                                STONE_STATE),
                        ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(1, 0, 0)))));
        assertEquals(ConstructionSiteAssessment.Status.ACCEPTED_CANDIDATE, accepted.status());
        assertTrue(accepted.findings().isEmpty());
    }

    @Test
    void rejectsForgedAssessmentValuesAndCanonicalizesVerifiedFindings() {
        ConstructionSiteBinding binding = binding(policyBlueprint());
        ConstructionSiteSurvey conflictingSurvey = new ConstructionSiteSurvey(binding, 9L, List.of(
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(-1, 0, 0),
                        DIRT_STATE),
                ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(0, 0, 0)),
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                        DIRT_STATE)));

        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteAssessment(conflictingSurvey,
                        ConstructionSiteAssessment.Status.ACCEPTED_CANDIDATE, List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteAssessment(conflictingSurvey,
                        ConstructionSiteAssessment.Status.BLOCKED, List.of(
                                new ConstructionSiteAssessment.Finding(new BlueprintOffset(-1, 0, 0),
                                        ConstructionSiteAssessment.FindingKind.PRESERVE_EXISTING_CONFLICT))));

        ConstructionSiteAssessment canonical = new ConstructionSiteAssessment(conflictingSurvey,
                ConstructionSiteAssessment.Status.BLOCKED, List.of(
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(1, 0, 0),
                                ConstructionSiteAssessment.FindingKind.HUMAN_CONFIRMATION_REQUIRED),
                        new ConstructionSiteAssessment.Finding(new BlueprintOffset(-1, 0, 0),
                                ConstructionSiteAssessment.FindingKind.PRESERVE_EXISTING_CONFLICT)));
        assertEquals(List.of(
                        new BlueprintOffset(-1, 0, 0),
                        new BlueprintOffset(1, 0, 0)),
                canonical.findings().stream().map(ConstructionSiteAssessment.Finding::offset).toList());
        assertThrows(UnsupportedOperationException.class,
                () -> canonical.findings().clear());
    }

    @Test
    void exactCoversAndAssessesA65CellMultiPackagePlan() {
        Blueprint blueprint = regularBlueprint(65);
        ConstructionSiteBinding binding = binding(blueprint);
        List<ConstructionSiteSurvey.TargetObservation> supplied = new ArrayList<>(
                observationsFor(blueprint, ConstructionSiteSurvey.TargetState.EMPTY));
        Collections.reverse(supplied);

        ConstructionSiteSurvey survey = new ConstructionSiteSurvey(binding, 1_000L, supplied);
        ConstructionSiteAssessment assessment = survey.assess();

        assertEquals(2, binding.workPlan().workPackages().size());
        assertEquals(65, survey.observations().size());
        assertEquals(65, survey.observations().stream()
                .map(ConstructionSiteSurvey.TargetObservation::offset)
                .distinct()
                .count());
        assertEquals(ConstructionSiteAssessment.Status.ACCEPTED_CANDIDATE, assessment.status());
        assertTrue(assessment.findings().isEmpty());
    }

    @Test
    void publicSurveyDtosStayPureAndExposeNoWorldLeaseOrExecutionAuthority() {
        List<Class<?>> dtoTypes = List.of(
                ConstructionSiteSurvey.class,
                ConstructionSiteSurvey.TargetObservation.class,
                ConstructionSiteAssessment.class,
                ConstructionSiteAssessment.Finding.class);

        for (Class<?> dtoType : dtoTypes) {
            assertTrue(dtoType.isRecord(), () -> dtoType.getName() + " must remain a record");
            for (RecordComponent component : dtoType.getRecordComponents()) {
                assertPureType(dtoType.getName() + "." + component.getName(),
                        component.getGenericType().getTypeName());
            }
            for (Method method : dtoType.getDeclaredMethods()) {
                assertPureType(dtoType.getName() + "." + method.getName(),
                        method.getGenericReturnType().getTypeName());
                for (Class<?> parameterType : method.getParameterTypes()) {
                    assertPureType(dtoType.getName() + "." + method.getName(),
                            parameterType.getTypeName());
                }
            }
        }
    }

    private static void assertPureType(String member, String typeName) {
        assertFalse(typeName.contains("net.minecraft"),
                () -> member + " contains a Minecraft runtime type");
        assertFalse(typeName.contains("Level"),
                () -> member + " contains world authority");
        assertFalse(typeName.contains("Lease"),
                () -> member + " contains a site lease");
        assertFalse(typeName.contains("Reservation"),
                () -> member + " contains reservation authority");
        assertFalse(typeName.contains("BotActionRuntime"),
                () -> member + " contains Action execution authority");
        assertFalse(typeName.contains("Technique"),
                () -> member + " contains Technique execution authority");
        assertFalse(typeName.contains("Skill"),
                () -> member + " contains Skill execution authority");
    }

    private static ConstructionSiteBinding binding(Blueprint blueprint) {
        return ConstructionSiteBinding.bind(SITE_ID, ConstructionWorkPlan.partition(blueprint),
                new ConstructionSiteAnchor(OVERWORLD, new BlockCoordinates(10, 64, -7)));
    }

    private static Blueprint policyBlueprint() {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 1L, List.of(
                cell(new BlueprintOffset(-1, 0, 0), BlueprintReplacePolicy.PRESERVE_EXISTING),
                cell(new BlueprintOffset(0, 0, 0), BlueprintReplacePolicy.REPLACE_OWNED_TEMPORARY),
                cell(new BlueprintOffset(1, 0, 0),
                        BlueprintReplacePolicy.REQUIRE_HUMAN_CONFIRMATION)));
    }

    private static Blueprint regularBlueprint(int cellCount) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 2L,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0),
                                BlueprintReplacePolicy.PRESERVE_EXISTING))
                        .toList());
    }

    private static List<ConstructionSiteSurvey.TargetObservation> observationsFor(
            Blueprint blueprint, ConstructionSiteSurvey.TargetState state) {
        return blueprint.cells().stream()
                .map(cell -> observation(cell.offset(), state))
                .toList();
    }

    private static ConstructionSiteSurvey.TargetObservation observation(
            BlueprintOffset offset, ConstructionSiteSurvey.TargetState state) {
        return switch (state) {
            case UNKNOWN -> ConstructionSiteSurvey.TargetObservation.unknown(offset);
            case EMPTY -> ConstructionSiteSurvey.TargetObservation.empty(offset);
            case OCCUPIED -> ConstructionSiteSurvey.TargetObservation.occupied(offset, STONE_STATE);
        };
    }

    private static BlueprintCell cell(BlueprintOffset offset, BlueprintReplacePolicy policy) {
        return new BlueprintCell(offset, STONE_STATE, BlueprintPlacementRole.FOUNDATION, policy,
                BlueprintMaterialClass.PERMANENT);
    }
}
