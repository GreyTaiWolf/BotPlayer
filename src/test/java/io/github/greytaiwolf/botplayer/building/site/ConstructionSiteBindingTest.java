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
import java.lang.reflect.RecordComponent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class ConstructionSiteBindingTest {
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000901");
    private static final UUID OTHER_BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000902");
    private static final UUID SITE_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000903");
    private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
    private static final ResourceId NETHER = new ResourceId("minecraft:the_nether");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");

    @Test
    void bindsExactPlanAndDerivesEveryTargetAndInclusiveBounds() {
        Blueprint blueprint = asymmetricBlueprint(BLUEPRINT_ID, 7L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionSiteAnchor anchor = new ConstructionSiteAnchor(OVERWORLD,
                new BlockCoordinates(100, 50, -100));

        ConstructionSiteBinding binding = ConstructionSiteBinding.bind(SITE_ID, plan, anchor);

        assertEquals(SITE_ID, binding.siteId());
        assertEquals(plan, binding.workPlan());
        assertEquals(anchor, binding.anchor());
        assertEquals(new ConstructionSiteBounds(OVERWORLD,
                        new BlockCoordinates(98, 49, -106),
                        new BlockCoordinates(105, 57, -96)),
                binding.constructionBounds());
        assertTrue(binding.constructionBounds().contains(new BlockCoordinates(98, 49, -106)));
        assertTrue(binding.constructionBounds().contains(new BlockCoordinates(105, 57, -96)));
        assertTrue(binding.constructionBounds().contains(OVERWORLD,
                new BlockCoordinates(98, 49, -106)));
        assertFalse(binding.constructionBounds().contains(NETHER,
                new BlockCoordinates(98, 49, -106)));
        assertThrows(NullPointerException.class,
                () -> binding.constructionBounds().contains(NETHER, null));
        assertFalse(binding.constructionBounds().contains(new BlockCoordinates(97, 49, -106)));
        assertFalse(binding.constructionBounds().contains(new BlockCoordinates(105, 58, -96)));
        assertEquals(new BlockCoordinates(98, 53, -96), binding.targetPosition(
                new BlueprintOffset(-2, 3, 4)));
        assertEquals(new BlockCoordinates(105, 49, -106), binding.targetPosition(
                new BlueprintOffset(5, -1, -6)));
        assertEquals(new BlockCoordinates(100, 57, -98), binding.targetPosition(
                new BlueprintOffset(0, 7, 2)));
        assertThrows(IllegalArgumentException.class,
                () -> binding.targetPosition(new BlueprintOffset(1, 1, 1)));
    }

    @Test
    void fencesFullPackageIdentityInsteadOfPartialBlueprintFields() {
        Blueprint blueprint = regularBlueprint(BLUEPRINT_ID, 65, 7L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionSiteBinding binding = ConstructionSiteBinding.bind(SITE_ID, plan,
                new ConstructionSiteAnchor(OVERWORLD, new BlockCoordinates(0, 64, 0)));

        assertTrue(plan.workPackages().stream().allMatch(workPackage ->
                binding.binds(workPackage.key())));

        ConstructionWorkPackageKey staleRevision = new ConstructionWorkPackageKey(
                blueprint.blueprintId(), blueprint.revision() + 1L,
                blueprint.contentHash(), 0);
        ConstructionWorkPackageKey staleHash = new ConstructionWorkPackageKey(
                blueprint.blueprintId(), blueprint.revision(), new BlueprintContentHash(
                        "0".repeat(BlueprintContentHash.HEX_LENGTH)), 0);
        ConstructionWorkPackageKey unknownOrdinal = new ConstructionWorkPackageKey(
                blueprint.blueprintId(), blueprint.revision(), blueprint.contentHash(), 2);
        Blueprint sameContentOtherIdentity = regularBlueprint(OTHER_BLUEPRINT_ID, 65, 7L);
        ConstructionWorkPackageKey otherIdentity = ConstructionWorkPackageKey.forBlueprint(
                sameContentOtherIdentity, 0);

        assertFalse(binding.binds(staleRevision));
        assertFalse(binding.binds(staleHash));
        assertFalse(binding.binds(unknownOrdinal));
        assertFalse(binding.binds(otherIdentity));
        assertThrows(NullPointerException.class, () -> binding.binds(null));

        ConstructionWorkPackage first = plan.workPackages().get(0);
        ConstructionWorkPackage second = plan.workPackages().get(1);
        BlueprintOffset firstOffset = first.cells().get(0).offset();
        BlueprintOffset secondOffset = second.cells().get(0).offset();
        assertEquals(binding.targetPosition(firstOffset), binding.targetPosition(first.key(),
                firstOffset));
        assertThrows(IllegalArgumentException.class,
                () -> binding.targetPosition(first.key(), secondOffset));
        assertThrows(IllegalArgumentException.class,
                () -> binding.targetPosition(staleRevision, firstOffset));
    }

    @Test
    void rejectsForgedBoundsAndCoordinateOverflowOrHorizontalEscape() {
        Blueprint blueprint = asymmetricBlueprint(BLUEPRINT_ID, 7L);
        ConstructionWorkPlan plan = ConstructionWorkPlan.partition(blueprint);
        ConstructionSiteAnchor anchor = new ConstructionSiteAnchor(OVERWORLD,
                new BlockCoordinates(100, 50, -100));
        ConstructionSiteBounds expected = ConstructionSiteBounds.forBlueprint(blueprint, anchor);

        assertThrows(IllegalArgumentException.class,
                () -> ConstructionSiteBinding.bind(new UUID(0L, 0L), plan, anchor));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteBounds(OVERWORLD,
                        new BlockCoordinates(1, 0, 0), new BlockCoordinates(0, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteBinding(SITE_ID, plan, anchor,
                        new ConstructionSiteBounds(NETHER, expected.minimum(),
                                expected.maximum())));
        assertThrows(IllegalArgumentException.class,
                () -> new ConstructionSiteBinding(SITE_ID, plan, anchor,
                        new ConstructionSiteBounds(OVERWORLD,
                                new BlockCoordinates(97, 49, -106), expected.maximum())));

        Blueprint positiveX = new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, 7L,
                List.of(cell(new BlueprintOffset(1, 0, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionSiteBinding.bind(SITE_ID,
                        ConstructionWorkPlan.partition(positiveX),
                        new ConstructionSiteAnchor(OVERWORLD,
                                new BlockCoordinates(BlockCoordinates.MAX_HORIZONTAL_COORDINATE,
                                        64, 0))));

        Blueprint negativeZ = new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, 7L,
                List.of(cell(new BlueprintOffset(0, 0, -1))));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionSiteBinding.bind(SITE_ID,
                        ConstructionWorkPlan.partition(negativeZ),
                        new ConstructionSiteAnchor(OVERWORLD,
                                new BlockCoordinates(0, 64,
                                        -BlockCoordinates.MAX_HORIZONTAL_COORDINATE))));

        Blueprint positiveY = new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, 7L,
                List.of(cell(new BlueprintOffset(0, 1, 0))));
        assertThrows(IllegalArgumentException.class,
                () -> ConstructionSiteBinding.bind(SITE_ID,
                        ConstructionWorkPlan.partition(positiveY),
                        new ConstructionSiteAnchor(OVERWORLD,
                                new BlockCoordinates(0, Integer.MAX_VALUE, 0))));
    }

    @Test
    void publicSiteDtosStayPureAndExposeNoExecutionOrReservationAuthority() {
        List<Class<?>> dtoTypes = List.of(
                ConstructionSiteAnchor.class,
                ConstructionSiteBounds.class,
                ConstructionSiteBinding.class);

        for (Class<?> dtoType : dtoTypes) {
            assertTrue(dtoType.isRecord(), () -> dtoType.getName() + " must remain a record");
            for (RecordComponent component : dtoType.getRecordComponents()) {
                String typeName = component.getGenericType().getTypeName();
                assertFalse(typeName.contains("net.minecraft"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains a Minecraft runtime type");
                assertFalse(typeName.contains("ItemStack"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains inventory runtime data");
                assertFalse(typeName.contains("BotActionRuntime"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains Action execution authority");
                assertFalse(typeName.contains("TechniqueActionPermit"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains Technique execution authority");
                assertFalse(typeName.contains("ReservationToken"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains reservation authority");
                assertFalse(typeName.contains("SkillCheckpoint"),
                        () -> dtoType.getName() + "." + component.getName()
                                + " contains checkpoint state");
            }
        }
    }

    private static Blueprint asymmetricBlueprint(UUID blueprintId, long revision) {
        return new Blueprint(blueprintId, Blueprint.CURRENT_SCHEMA_VERSION, revision, List.of(
                cell(new BlueprintOffset(-2, 3, 4)),
                cell(new BlueprintOffset(5, -1, -6)),
                cell(new BlueprintOffset(0, 7, 2))));
    }

    private static Blueprint regularBlueprint(UUID blueprintId, int cellCount, long revision) {
        return new Blueprint(blueprintId, Blueprint.CURRENT_SCHEMA_VERSION, revision,
                IntStream.range(0, cellCount)
                        .mapToObj(index -> cell(new BlueprintOffset(index % 16, index / 16, 0)))
                        .toList());
    }

    private static BlueprintCell cell(BlueprintOffset offset) {
        return new BlueprintCell(offset, new BlockStateFingerprint(STONE, Map.of()),
                BlueprintPlacementRole.FOUNDATION, BlueprintReplacePolicy.PRESERVE_EXISTING,
                BlueprintMaterialClass.PERMANENT);
    }
}
