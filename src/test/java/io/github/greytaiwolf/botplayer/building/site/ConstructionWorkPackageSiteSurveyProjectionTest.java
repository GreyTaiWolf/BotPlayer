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
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackage;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackageKey;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionWorkPackageSiteSurveyProjectionTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000ab1");
    private static final UUID SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000ab2");
    private static final UUID OTHER_SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000ab3");
    private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId DIRT = new ResourceId("minecraft:dirt");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE, Map.of());
    private static final BlockStateFingerprint DIRT_STATE = new BlockStateFingerprint(DIRT, Map.of());

    @Test
    void projectsEveryActualPackageTargetAndRawSurveyObservationFor65And256CellPlans() {
        for (int cellCount : List.of(65, 256)) {
            Blueprint blueprint = regularBlueprint(cellCount, cellCount);
            ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
            ConstructionSiteBinding binding = binding(plan, SITE_ID,
                    new BlockCoordinates(100, 64, -100));
            ConstructionSiteSurvey survey = patternedSurvey(binding, 1_000L + cellCount);
            List<ConstructionWorkPackageSiteSurveyProjection> projections = plan.workPackages()
                    .stream()
                    .map(workPackage -> ConstructionWorkPackageSiteSurveyProjection.project(
                            ConstructionWorkPackageSiteTargetProjection.project(binding,
                                    workPackage.key()),
                            survey))
                    .toList();

            assertEquals(cellCount, projections.stream().mapToInt(
                    ConstructionWorkPackageSiteSurveyProjection::observationCount).sum());
            assertEquals(plan.workPackages().stream().map(ConstructionWorkPackage::key).toList(),
                    projections.stream().map(projection -> projection.targetManifest()
                            .workPackageKey()).toList());
            for (ConstructionWorkPackageSiteSurveyProjection projection : projections) {
                assertEquals(survey, projection.survey());
                assertEquals(survey.observedTick(), projection.observedTick());
                assertEquals(projection.targetManifest().targets(), projection.observations().stream()
                        .map(ConstructionWorkPackageSiteSurveyProjection.TargetObservation::target)
                        .toList());
                for (ConstructionWorkPackageSiteSurveyProjection.TargetObservation paired
                        : projection.observations()) {
                    ConstructionSiteSurvey.TargetObservation expected = survey.observations().stream()
                            .filter(observation -> observation.offset().equals(
                                    paired.target().cell().offset()))
                            .findFirst()
                            .orElseThrow();
                    assertEquals(expected, paired.observation());
                    assertEquals(projection.targetManifest().binding().targetPosition(
                                    projection.targetManifest().workPackageKey(),
                                    paired.target().cell().offset()),
                            paired.target().targetPosition());
                }
            }
            assertThrows(UnsupportedOperationException.class,
                    () -> projections.getFirst().observations().clear());
        }
    }

    @Test
    void preservesUnknownEmptyAndOccupiedEvidenceWithoutDerivingAReadyOrAcceptedStatus() {
        Blueprint blueprint = new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 2L,
                List.of(
                        cell(new BlueprintOffset(0, 0, 0)),
                        cell(new BlueprintOffset(1, 0, 0)),
                        cell(new BlueprintOffset(2, 0, 0))));
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionSiteBinding binding = binding(plan, SITE_ID, new BlockCoordinates(0, 64, 0));
        ConstructionSiteSurvey survey = new ConstructionSiteSurvey(binding, 42L, List.of(
                ConstructionSiteSurvey.TargetObservation.empty(new BlueprintOffset(2, 0, 0)),
                ConstructionSiteSurvey.TargetObservation.occupied(new BlueprintOffset(1, 0, 0),
                        DIRT_STATE),
                ConstructionSiteSurvey.TargetObservation.unknown(new BlueprintOffset(0, 0, 0))));

        ConstructionWorkPackageSiteSurveyProjection projection =
                ConstructionWorkPackageSiteSurveyProjection.project(
                        ConstructionWorkPackageSiteTargetProjection.project(binding,
                                plan.workPackages().getFirst().key()),
                        survey);

        assertEquals(42L, projection.observedTick());
        assertEquals(List.of(
                        ConstructionSiteSurvey.TargetState.UNKNOWN,
                        ConstructionSiteSurvey.TargetState.OCCUPIED,
                        ConstructionSiteSurvey.TargetState.EMPTY),
                projection.observations().stream()
                        .map(pair -> pair.observation().state())
                        .toList());
        assertEquals(DIRT_STATE, projection.observations().get(1).observation()
                .observedState().orElseThrow());
        assertFalse(Arrays.stream(ConstructionWorkPackageSiteSurveyProjection.class
                        .getDeclaredMethods())
                .map(Method::getName)
                .anyMatch(name -> name.equals("status")
                        || name.equals("accepted")
                        || name.equals("ready")
                        || name.equals("placeableCount")
                        || name.equals("remainingCount")));
    }

    @Test
    void rejectsAlternativePartitionsAndBindingDriftEvenWhenBlueprintAndOrdinalMatch() {
        Blueprint blueprint = regularBlueprint(65, 3L);
        ConstructionWorkPlan defaultPlan = ConstructionWorkPlan.partition(blueprint);
        ConstructionWorkPackageKey firstKey = ConstructionWorkPackageKey.forBlueprint(blueprint, 0);
        ConstructionWorkPackageKey secondKey = ConstructionWorkPackageKey.forBlueprint(blueprint, 1);
        ConstructionWorkPlan alternatePlan = new ConstructionWorkPlan(blueprint, List.of(
                new ConstructionWorkPackage(firstKey, blueprint.cells().subList(0, 16), List.of()),
                new ConstructionWorkPackage(secondKey, blueprint.cells().subList(16, 65),
                        List.of(firstKey))));
        ConstructionSiteBinding defaultBinding = binding(defaultPlan, SITE_ID,
                new BlockCoordinates(5, 64, -5));
        ConstructionSiteBinding alternateBinding = binding(alternatePlan, SITE_ID,
                new BlockCoordinates(5, 64, -5));
        ConstructionWorkPackageSiteTargetProjection alternateManifest =
                ConstructionWorkPackageSiteTargetProjection.project(alternateBinding, firstKey);

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteSurveyProjection.project(alternateManifest,
                        patternedSurvey(defaultBinding, 7L)));
        assertEquals(16, ConstructionWorkPackageSiteSurveyProjection.project(alternateManifest,
                patternedSurvey(alternateBinding, 7L)).observationCount());
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteSurveyProjection.project(
                        ConstructionWorkPackageSiteTargetProjection.project(defaultBinding, firstKey),
                        patternedSurvey(binding(defaultPlan, OTHER_SITE_ID,
                                new BlockCoordinates(5, 64, -5)), 7L)));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteSurveyProjection.project(
                        ConstructionWorkPackageSiteTargetProjection.project(defaultBinding, firstKey),
                        patternedSurvey(binding(defaultPlan, SITE_ID,
                                new BlockCoordinates(6, 64, -5)), 7L)));
        Blueprint revisionDrift = new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 4L,
                blueprint.cells());
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteSurveyProjection.project(
                        ConstructionWorkPackageSiteTargetProjection.project(defaultBinding, firstKey),
                        patternedSurvey(binding(ConstructionWorkPlan.partition(revisionDrift), SITE_ID,
                                new BlockCoordinates(5, 64, -5)), 7L)));
        assertThrows(NullPointerException.class,
                () -> ConstructionWorkPackageSiteSurveyProjection.project(alternateManifest, null));
        assertThrows(NullPointerException.class,
                () -> ConstructionWorkPackageSiteSurveyProjection.project(null,
                        patternedSurvey(alternateBinding, 7L)));
    }

    @Test
    void privateConstructorRejectsForgedOrReorderedTargetObservationPairs() throws Exception {
        Blueprint blueprint = regularBlueprint(3, 4L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionSiteBinding binding = binding(plan, SITE_ID, new BlockCoordinates(0, 64, 0));
        ConstructionSiteSurvey survey = patternedSurvey(binding, 8L);
        ConstructionWorkPackageSiteTargetProjection manifest =
                ConstructionWorkPackageSiteTargetProjection.project(binding,
                        plan.workPackages().getFirst().key());
        ConstructionWorkPackageSiteSurveyProjection projection =
                ConstructionWorkPackageSiteSurveyProjection.project(manifest, survey);
        List<ConstructionWorkPackageSiteSurveyProjection.TargetObservation> observed =
                projection.observations();

        assertPrivateConstructorRejects(manifest, survey, List.of(observed.get(1),
                observed.getFirst(), observed.get(2)));
        ConstructionWorkPackageSiteTargetProjection.Target forgedTarget =
                new ConstructionWorkPackageSiteTargetProjection.Target(
                        observed.getFirst().target().cell(), new BlockCoordinates(9, 80, -9));
        assertPrivateConstructorRejects(manifest, survey, List.of(
                new ConstructionWorkPackageSiteSurveyProjection.TargetObservation(forgedTarget,
                        observed.getFirst().observation()),
                observed.get(1),
                observed.get(2)));
        assertPrivateConstructorRejects(manifest, survey, List.of(
                new ConstructionWorkPackageSiteSurveyProjection.TargetObservation(
                        observed.getFirst().target(), observed.get(1).observation()),
                observed.get(1),
                observed.get(2)));
        assertPrivateConstructorRejects(manifest, survey, List.of(observed.getFirst(),
                observed.get(1)));
    }

    @Test
    void projectionStaysPureAndHasNoPublicForgingConstructor() {
        assertTrue(Modifier.isFinal(ConstructionWorkPackageSiteSurveyProjection.class
                .getModifiers()));
        assertEquals(0, ConstructionWorkPackageSiteSurveyProjection.class.getConstructors().length);
        assertPureDtoType(ConstructionWorkPackageSiteSurveyProjection.class);
        assertPureDtoType(ConstructionWorkPackageSiteSurveyProjection.TargetObservation.class);
    }

    private static void assertPrivateConstructorRejects(
            ConstructionWorkPackageSiteTargetProjection manifest,
            ConstructionSiteSurvey survey,
            List<ConstructionWorkPackageSiteSurveyProjection.TargetObservation> supplied)
            throws Exception {
        Constructor<ConstructionWorkPackageSiteSurveyProjection> constructor =
                ConstructionWorkPackageSiteSurveyProjection.class.getDeclaredConstructor(
                        ConstructionWorkPackageSiteTargetProjection.class,
                        ConstructionSiteSurvey.class,
                        List.class);
        constructor.setAccessible(true);
        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> constructor.newInstance(manifest, survey, supplied));
        assertTrue(failure.getCause() instanceof IllegalArgumentException);
    }

    private static void assertPureDtoType(Class<?> type) {
        if (type.isRecord()) {
            for (RecordComponent component : type.getRecordComponents()) {
                assertPureType(type.getName() + "." + component.getName(),
                        component.getGenericType().getTypeName());
            }
        }
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            for (Class<?> parameterType : constructor.getParameterTypes()) {
                assertPureType(type.getName() + ".<init>", parameterType.getTypeName());
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            assertPureType(type.getName() + "." + method.getName(),
                    method.getGenericReturnType().getTypeName());
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertPureType(type.getName() + "." + method.getName(),
                        parameterType.getTypeName());
            }
        }
    }

    private static void assertPureType(String member, String typeName) {
        assertFalse(typeName.contains("net.minecraft"),
                () -> member + " contains a Minecraft runtime type");
        assertFalse(typeName.contains("ItemStack"),
                () -> member + " contains an item runtime type");
        assertFalse(typeName.contains("Reservation"),
                () -> member + " contains reservation authority");
        assertFalse(typeName.contains("ConstructionSiteAssessment"),
                () -> member + " contains site-assessment authority");
        assertFalse(typeName.contains("ConstructionSiteLease"),
                () -> member + " contains a site-lease dependency");
        assertFalse(typeName.contains("ConstructionMaterial"),
                () -> member + " contains a material dependency");
        assertFalse(typeName.contains("ConstructionWorkPackageMaterial"),
                () -> member + " contains a material-demand dependency");
        assertFalse(typeName.contains("BotActionRuntime"),
                () -> member + " contains action authority");
        assertFalse(typeName.contains("Technique"),
                () -> member + " contains technique authority");
        assertFalse(typeName.contains("Skill"),
                () -> member + " contains skill authority");
    }

    private static ConstructionSiteBinding binding(
            ConstructionWorkPlan plan, UUID siteId, BlockCoordinates origin) {
        return ConstructionSiteBinding.bind(siteId, plan, new ConstructionSiteAnchor(OVERWORLD,
                origin));
    }

    private static ConstructionSiteSurvey patternedSurvey(
            ConstructionSiteBinding binding, long observedTick) {
        List<ConstructionSiteSurvey.TargetObservation> observations = new ArrayList<>();
        List<BlueprintCell> cells = binding.workPlan().blueprint().cells();
        for (int index = 0; index < cells.size(); index++) {
            BlueprintCell cell = cells.get(index);
            switch (index % 3) {
                case 0 -> observations.add(ConstructionSiteSurvey.TargetObservation.unknown(
                        cell.offset()));
                case 1 -> observations.add(ConstructionSiteSurvey.TargetObservation.empty(
                        cell.offset()));
                default -> observations.add(ConstructionSiteSurvey.TargetObservation.occupied(
                        cell.offset(), cell.expectedState()));
            }
        }
        Collections.reverse(observations);
        return new ConstructionSiteSurvey(binding, observedTick, observations);
    }

    private static Blueprint regularBlueprint(int cellCount, long revision) {
        return new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, revision,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0)))
                        .toList());
    }

    private static BlueprintCell cell(BlueprintOffset offset) {
        return new BlueprintCell(offset, STONE_STATE, BlueprintPlacementRole.STRUCTURE,
                BlueprintReplacePolicy.PRESERVE_EXISTING, BlueprintMaterialClass.PERMANENT);
    }
}
