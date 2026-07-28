package io.github.greytaiwolf.botplayer.action.interaction;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class InteractionFingerprintTest {
   private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
   private static final ResourceId OAK_LOG = new ResourceId("minecraft:oak_log");

   @Test
   void resourceIdsRequireExplicitBoundedLowercaseNames() {
      Assertions.assertEquals("minecraft:oak_log", OAK_LOG.toString());
      Assertions.assertEquals(256, new ResourceId("n".repeat(64) + ":" + "p".repeat(191)).value().length());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ResourceId("oak_log"));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ResourceId("Minecraft:oak_log"));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ResourceId("minecraft:Oak Log"));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ResourceId("n".repeat(65) + ":path"));
   }

   @Test
   void blockCoordinatesApplyHorizontalLimitWithoutGuessingDimensionHeight() {
      BlockCoordinates var1 = new BlockCoordinates(-30000000, -10000, -17);
      BlockCoordinates var2 = new BlockCoordinates(30000000, 10000, 31);
      Assertions.assertEquals(-2, var1.chunkZ());
      Assertions.assertEquals(1, var2.chunkZ());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockCoordinates(30000001, 0, 0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockCoordinates(Integer.MIN_VALUE, 0, 0));
   }

   @Test
   void blockStatePropertiesAreCanonicalAndDefensivelyCopied() {
      LinkedHashMap<String, String> var1 = new LinkedHashMap<>();
      var1.put("waterlogged", "false");
      var1.put("axis", "y");
      BlockStateFingerprint var2 = new BlockStateFingerprint(OAK_LOG, var1);
      var1.put("axis", "x");
      Assertions.assertEquals(List.of("axis", "waterlogged"), List.copyOf(var2.properties().keySet()));
      Assertions.assertEquals("y", var2.properties().get("axis"));
      Assertions.assertThrows(UnsupportedOperationException.class, () -> var2.properties().put("axis", "x"));
      Assertions.assertEquals(var2, new BlockStateFingerprint(OAK_LOG, Map.of("axis", "y", "waterlogged", "false")));
   }

   @Test
   void blockStatePropertiesRejectUnsafeAndOversizedInput() {
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockStateFingerprint(OAK_LOG, Map.of("bad key", "x")));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockStateFingerprint(OAK_LOG, Map.of("axis", "UPPER")));
      LinkedHashMap<String, String> var1 = new LinkedHashMap<>();

      for (int var2 = 0; var2 <= 64; var2++) {
         var1.put("p" + var2, "v");
      }

      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockStateFingerprint(OAK_LOG, var1));
      LinkedHashMap<String, String> var4 = new LinkedHashMap<>();

      for (int var3 = 0; var3 < 20; var3++) {
         var4.put("property_" + var3 + "_" + "k".repeat(40), "v".repeat(128));
      }

      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockStateFingerprint(OAK_LOG, var4));
   }

   @Test
   void itemFingerprintsRepresentEmptyExplicitlyAndRequireStrongDigestShape() {
      String var1 = "a".repeat(64);
      ItemStackFingerprint var2 = ItemStackFingerprint.of(new ResourceId("minecraft:diamond_pickaxe"), 1, 42, var1);
      Assertions.assertFalse(var2.isEmpty());
      Assertions.assertEquals(Optional.of(var1), var2.componentsDigest());
      Assertions.assertTrue(ItemStackFingerprint.empty().isEmpty());
      Assertions.assertEquals(ItemStackFingerprint.empty(), ItemStackFingerprint.empty());
      Assertions.assertThrows(IllegalArgumentException.class, () -> ItemStackFingerprint.of(OAK_LOG, 0, 0, var1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> ItemStackFingerprint.of(OAK_LOG, 1, -1, var1));
      Assertions.assertThrows(IllegalArgumentException.class, () -> ItemStackFingerprint.of(OAK_LOG, 1, 0, "A".repeat(64)));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ItemStackFingerprint(Optional.empty(), 1, 0, Optional.empty()));
   }

   @Test
   void blockHitUsesLocalCoordinatesAndExactTargetFingerprint() {
      BlockCoordinates var1 = new BlockCoordinates(10, 64, -4);
      BlockTargetFingerprint var2 = new BlockTargetFingerprint(OVERWORLD, var1, new BlockStateFingerprint(OAK_LOG, Map.of("axis", "y")));
      BlockHitTarget var3 = new BlockHitTarget(var2, BlockHitTarget.Face.UP, 0.25, 1.0, 0.75, false);
      Assertions.assertEquals(10.25, var3.worldX());
      Assertions.assertEquals(65.0, var3.worldY());
      Assertions.assertEquals(-3.25, var3.worldZ());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockHitTarget(var2, BlockHitTarget.Face.UP, -0.01, 0.5, 0.5, false));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new BlockHitTarget(var2, BlockHitTarget.Face.UP, Double.NaN, 0.5, 0.5, false));
   }

   @Test
   void entityTargetsUseStableUuidAndBoundedLocalHit() {
      UUID var1 = new UUID(0L, 99L);
      EntityTargetFingerprint var2 = new EntityTargetFingerprint(OVERWORLD, var1, new ResourceId("minecraft:villager"));
      Assertions.assertEquals(var1, var2.entityId());
      Assertions.assertEquals(new EntityLocalHit(0.25, 1.5, -0.5), new EntityLocalHit(0.25, 1.5, -0.5));
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new EntityTargetFingerprint(OVERWORLD, new UUID(0L, 0L), new ResourceId("minecraft:villager"))
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> new EntityLocalHit(1025.0, 0.0, 0.0));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new EntityLocalHit(0.0, Double.POSITIVE_INFINITY, 0.0));
   }

   @Test
   void publicInteractionDtoComponentsContainNoMinecraftRuntimeTypes() {
      ArrayList<Class<?>> var1 = new ArrayList<>(
         List.of(
            ResourceId.class,
            BlockCoordinates.class,
            BlockStateFingerprint.class,
            BlockTargetFingerprint.class,
            ItemStackFingerprint.class,
            EntityTargetFingerprint.class,
            BlockHitTarget.class,
            EntityLocalHit.class,
            InteractionPreflight.Requirements.class,
            InteractionPreflight.Observation.class,
            InteractionPreflight.Result.class
         )
      );
      var1.addAll(Arrays.asList(WorldInteractionActionSpec.class.getPermittedSubclasses()));

      for (Class<?> var3 : var1) {
         Assertions.assertTrue(var3.isRecord(), () -> var3.getName() + " must remain a record");

         for (RecordComponent var7 : var3.getRecordComponents()) {
            String var8 = var7.getGenericType().getTypeName();
            Assertions.assertFalse(var8.contains("net.minecraft"), () -> var3.getName() + "." + var7.getName() + " contains a Minecraft runtime type");
         }
      }
   }
}
