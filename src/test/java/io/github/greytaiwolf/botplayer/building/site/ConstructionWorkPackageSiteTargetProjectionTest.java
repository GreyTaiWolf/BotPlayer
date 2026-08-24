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
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintContentHash;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackage;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPackageKey;
import io.github.greytaiwolf.botplayer.building.construction.ConstructionWorkPlan;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionWorkPackageSiteTargetProjectionTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000aa1");
    private static final UUID OTHER_BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000aa2");
    private static final UUID SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000aa3");
    private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE, Map.of());

    @Test
    void projectsEveryActualPackageCellAndCandidateCoordinateFor65And256CellPlans() {
        for (int cellCount : List.of(65, 256)) {
            Blueprint blueprint = regularBlueprint(BLUEPRINT_ID, cellCount, cellCount);
            ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
            ConstructionSiteBinding binding = binding(plan);
            List<ConstructionWorkPackageSiteTargetProjection> projections = plan.workPackages()
                    .stream()
                    .map(workPackage -> ConstructionWorkPackageSiteTargetProjection.project(
                            binding, workPackage.key()))
                    .toList();

            assertEquals(cellCount, projections.stream().mapToInt(
                    ConstructionWorkPackageSiteTargetProjection::targetCount).sum());
            assertEquals(plan.workPackages().stream().map(ConstructionWorkPackage::key).toList(),
                    projections.stream().map(
                            ConstructionWorkPackageSiteTargetProjection::workPackageKey).toList());
            for (ConstructionWorkPackageSiteTargetProjection projection : projections) {
                ConstructionWorkPackage workPackage = plan.workPackages().stream()
                        .filter(candidate -> candidate.key().equals(projection.workPackageKey()))
                        .findFirst()
                        .orElseThrow();
                assertEquals(workPackage.cells(), projection.targets().stream()
                        .map(ConstructionWorkPackageSiteTargetProjection.Target::cell)
                        .toList());
                assertEquals(workPackage.cells().stream()
                                .map(cell -> binding.targetPosition(workPackage.key(),
                                        cell.offset()))
                                .toList(),
                        projection.targets().stream()
                                .map(ConstructionWorkPackageSiteTargetProjection.Target
                                        ::targetPosition)
                                .toList());
                assertTrue(projection.targets().stream().allMatch(target -> binding
                        .constructionBounds().contains(binding.anchor().dimension(),
                                target.targetPosition())));
            }
            assertThrows(UnsupportedOperationException.class,
                    () -> projections.getFirst().targets().clear());
        }
    }

    @Test
    void usesTheBindingPlanInsteadOfRepartitioningTheSameBlueprintByOrdinal() {
        Blueprint blueprint = regularBlueprint(BLUEPRINT_ID, 65, 5L);
        ConstructionWorkPlan defaultPlan = ConstructionWorkPlan.partition(blueprint);
        ConstructionWorkPackageKey firstKey = ConstructionWorkPackageKey.forBlueprint(blueprint, 0);
        ConstructionWorkPackageKey secondKey = ConstructionWorkPackageKey.forBlueprint(blueprint, 1);
        ConstructionWorkPlan alternatePlan = new ConstructionWorkPlan(blueprint, List.of(
                new ConstructionWorkPackage(firstKey, blueprint.cells().subList(0, 16), List.of()),
                new ConstructionWorkPackage(secondKey, blueprint.cells().subList(16, 65),
                        List.of(firstKey))));
        ConstructionSiteBinding binding = binding(alternatePlan);

        ConstructionWorkPackageSiteTargetProjection projection =
                ConstructionWorkPackageSiteTargetProjection.project(binding, firstKey);

        assertEquals(alternatePlan.workPackages().getFirst().cells(), projection.targets().stream()
                .map(ConstructionWorkPackageSiteTargetProjection.Target::cell)
                .toList());
        assertEquals(16, projection.targetCount());
        assertFalse(defaultPlan.workPackages().getFirst().cells().equals(projection.targets()
                .stream()
                .map(ConstructionWorkPackageSiteTargetProjection.Target::cell)
                .toList()));
    }

    @Test
    void rejectsForeignOrStaleWorkPackageIdentities() {
        Blueprint blueprint = regularBlueprint(BLUEPRINT_ID, 65, 6L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionSiteBinding binding = binding(plan);
        ConstructionWorkPackageKey actual = plan.workPackages().getFirst().key();
        ConstructionWorkPackageKey staleRevision = new ConstructionWorkPackageKey(
                blueprint.blueprintId(), blueprint.revision() + 1L, blueprint.contentHash(), 0);
        ConstructionWorkPackageKey staleHash = new ConstructionWorkPackageKey(blueprint.blueprintId(),
                blueprint.revision(), new BlueprintContentHash("0".repeat(
                        BlueprintContentHash.HEX_LENGTH)), 0);
        ConstructionWorkPackageKey missingOrdinal = ConstructionWorkPackageKey.forBlueprint(blueprint,
                plan.workPackages().size());
        ConstructionWorkPackageKey foreignIdentity = ConstructionWorkPackageKey.forBlueprint(
                regularBlueprint(OTHER_BLUEPRINT_ID, 65, 6L), actual.ordinal());

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteTargetProjection.project(binding, staleRevision));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteTargetProjection.project(binding, staleHash));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteTargetProjection.project(binding, missingOrdinal));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionWorkPackageSiteTargetProjection.project(binding, foreignIdentity));
        assertThrows(NullPointerException.class,
                () -> ConstructionWorkPackageSiteTargetProjection.project(binding, null));
    }

    @Test
    void targetProjectionStaysPureAndHasNoPublicForgingConstructor() {
        assertTrue(Modifier.isFinal(ConstructionWorkPackageSiteTargetProjection.class
                .getModifiers()));
        assertEquals(0, ConstructionWorkPackageSiteTargetProjection.class.getConstructors().length);
        assertPureDtoType(ConstructionWorkPackageSiteTargetProjection.class);
        assertPureDtoType(ConstructionWorkPackageSiteTargetProjection.Target.class);
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
        assertFalse(typeName.contains("ConstructionSiteSurvey"),
                () -> member + " contains a site-survey dependency");
        assertFalse(typeName.contains("ConstructionSiteAssessment"),
                () -> member + " contains a site-assessment dependency");
        assertFalse(typeName.contains("ConstructionSiteLease"),
                () -> member + " contains a site-lease dependency");
        assertFalse(typeName.contains("ConstructionMaterialAvailability"),
                () -> member + " contains a material-observation dependency");
        assertFalse(typeName.contains("ConstructionWorkPackageMaterial"),
                () -> member + " contains a material-demand dependency");
        assertFalse(typeName.contains("BotActionRuntime"),
                () -> member + " contains action authority");
        assertFalse(typeName.contains("Technique"),
                () -> member + " contains technique authority");
        assertFalse(typeName.contains("Skill"),
                () -> member + " contains skill authority");
    }

    private static ConstructionSiteBinding binding(ConstructionWorkPlan plan) {
        return ConstructionSiteBinding.bind(SITE_ID, plan, new ConstructionSiteAnchor(OVERWORLD,
                new BlockCoordinates(100, 64, -100)));
    }

    private static Blueprint regularBlueprint(UUID blueprintId, int cellCount, long revision) {
        return new Blueprint(blueprintId, Blueprint.CURRENT_SCHEMA_VERSION, revision,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0)))
                        .toList());
    }

    private static BlueprintCell cell(BlueprintOffset offset) {
        return new BlueprintCell(offset, STONE_STATE, BlueprintPlacementRole.STRUCTURE,
                BlueprintReplacePolicy.PRESERVE_EXISTING, BlueprintMaterialClass.PERMANENT);
    }
}
