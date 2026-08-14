package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class WorldInteractionActionSpecTest {
   private static final ResourceId OVERWORLD = new ResourceId("minecraft:overworld");
   private static final ItemStackFingerprint EMPTY = ItemStackFingerprint.empty();
   private static final ItemStackFingerprint STICK = ItemStackFingerprint.of(new ResourceId("minecraft:stick"), 1, 0, "0".repeat(64));
   private static final ItemStackFingerprint OAK_PLANKS = ItemStackFingerprint.of(
      new ResourceId("minecraft:oak_planks"), 3, 0, "1".repeat(64)
   );

   @Test
   void hotbarSelectionHasStrictSlotAndOwnsInventoryAndMainHand() {
      WorldInteractionActionSpec.SelectHotbar var1 = new WorldInteractionActionSpec.SelectHotbar(8, STICK);
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.SELECT_HOTBAR, var1.kind());
      Assertions.assertEquals(Set.of(ActionChannel.INVENTORY, ActionChannel.MAIN_HAND), var1.channels());
      Assertions.assertThrows(UnsupportedOperationException.class, () -> var1.channels().remove(ActionChannel.INVENTORY));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SelectHotbar(-1, EMPTY));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SelectHotbar(9, EMPTY));
   }

   @Test
   void inventoryHotbarSwapHasStrictSlotsFingerprintsAndChannels() {
      WorldInteractionActionSpec.SwapInventoryHotbar var1 = new WorldInteractionActionSpec.SwapInventoryHotbar(9, 8, STICK, EMPTY);
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.SWAP_INVENTORY_HOTBAR, var1.kind());
      Assertions.assertEquals(ActionKind.SWAP_INVENTORY_HOTBAR, new WorldInteractionAction(var1).kind());
      Assertions.assertEquals(Set.of(ActionChannel.INVENTORY, ActionChannel.MAIN_HAND), var1.channels());
      Assertions.assertEquals(STICK, var1.expectedSource());
      Assertions.assertEquals(EMPTY, var1.expectedTarget());
      Assertions.assertThrows(UnsupportedOperationException.class, () -> var1.channels().remove(ActionChannel.INVENTORY));
      Assertions.assertDoesNotThrow(() -> new WorldInteractionActionSpec.SwapInventoryHotbar(35, 0, EMPTY, STICK));
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(8, 0, STICK, EMPTY)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(36, 0, STICK, EMPTY)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(9, -1, STICK, EMPTY)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(9, 9, STICK, EMPTY)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(9, 0, null, EMPTY)
      );
      Assertions.assertThrows(
         NullPointerException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(9, 0, STICK, null)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class, () -> new WorldInteractionActionSpec.SwapInventoryHotbar(9, 0, STICK, STICK)
      );
   }

   @Test
   void useModeMakesContinuedUseAndReleasePolicyExplicit() {
      WorldInteractionActionSpec.UseItem var1 = new WorldInteractionActionSpec.UseItem(
         WorldInteractionActionSpec.Hand.MAIN_HAND, STICK, WorldInteractionActionSpec.ItemUseMode.INSTANT, 0
      );
      WorldInteractionActionSpec.UseItem var2 = new WorldInteractionActionSpec.UseItem(
         WorldInteractionActionSpec.Hand.OFF_HAND, STICK, WorldInteractionActionSpec.ItemUseMode.RELEASE_AFTER_HOLD, 20
      );
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.USE_ITEM, var1.kind());
      Assertions.assertEquals(Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT), var1.channels());
      Assertions.assertEquals(Set.of(ActionChannel.OFF_HAND, ActionChannel.INTERACT), var2.channels());
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.UseItem(WorldInteractionActionSpec.Hand.MAIN_HAND, STICK, WorldInteractionActionSpec.ItemUseMode.INSTANT, 1)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.UseItem(
               WorldInteractionActionSpec.Hand.MAIN_HAND, STICK, WorldInteractionActionSpec.ItemUseMode.RELEASE_AFTER_HOLD, 0
            )
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.UseItem(
               WorldInteractionActionSpec.Hand.MAIN_HAND, STICK, WorldInteractionActionSpec.ItemUseMode.RELEASE_AFTER_HOLD, 6001
            )
      );
   }

   @Test
   void strictUseItemPreconditionsAreOptionalBoundedAndCanonical() {
      ActiveEffectFingerprint poison = new ActiveEffectFingerprint(
         new ResourceId("minecraft:poison"), 0, 80, false, true
      );
      ActiveEffectFingerprint speed = new ActiveEffectFingerprint(
         new ResourceId("minecraft:speed"), 1, 0, false, true
      );
      UseItemPreconditions strict = new UseItemPreconditions(
         nativeInventorySnapshot(), List.of(speed, poison)
      );
      WorldInteractionActionSpec.UseItem strictUse = new WorldInteractionActionSpec.UseItem(
         WorldInteractionActionSpec.Hand.MAIN_HAND,
         STICK,
         WorldInteractionActionSpec.ItemUseMode.FINISH_NATURALLY,
         0,
         strict
      );
      WorldInteractionActionSpec.UseItem legacy = new WorldInteractionActionSpec.UseItem(
         WorldInteractionActionSpec.Hand.MAIN_HAND,
         STICK,
         WorldInteractionActionSpec.ItemUseMode.FINISH_NATURALLY,
         0
      );
      WorldInteractionActionSpec.UseItem strictOffHand = new WorldInteractionActionSpec.UseItem(
         WorldInteractionActionSpec.Hand.OFF_HAND,
         STICK,
         WorldInteractionActionSpec.ItemUseMode.FINISH_NATURALLY,
         0,
         strict
      );

      Assertions.assertEquals(List.of(poison, speed), strict.activeEffects());
      Assertions.assertEquals(strict, strictUse.strictPreconditions().orElseThrow());
      Assertions.assertTrue(legacy.strictPreconditions().isEmpty());
      Assertions.assertEquals(
         Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT, ActionChannel.INVENTORY),
         strictUse.channels()
      );
      Assertions.assertEquals(
         Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT),
         legacy.channels()
      );
      Assertions.assertEquals(
         Set.of(ActionChannel.OFF_HAND, ActionChannel.INTERACT, ActionChannel.INVENTORY),
         strictOffHand.channels()
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> new UseItemPreconditions(
         nativeInventorySnapshot(), List.of(poison, poison)
      ));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new ActiveEffectFingerprint(
         new ResourceId("minecraft:poison"), 0, -1, false, true
      ));
   }

   @Test
   void releaseDropAndPickupRejectAmbiguousEmptyOrUnboundedRequests() {
      Assertions.assertEquals(
         WorldInteractionActionSpec.Kind.RELEASE_USE, new WorldInteractionActionSpec.ReleaseUse(WorldInteractionActionSpec.Hand.MAIN_HAND, STICK).kind()
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.ReleaseUse(WorldInteractionActionSpec.Hand.MAIN_HAND, EMPTY));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.DropSelected(false, EMPTY));
      WorldInteractionActionSpec.PickupWait var1 = new WorldInteractionActionSpec.PickupWait(40, Optional.of(new UUID(0L, 9L)));
      Assertions.assertEquals(Set.of(ActionChannel.INVENTORY), var1.channels());
      Assertions.assertEquals(Optional.of(new UUID(0L, 9L)), var1.expectedItemEntityId());
      WorldInteractionActionSpec.PickupWait grouped = new WorldInteractionActionSpec.PickupWait(
         40, List.of(new UUID(0L, 9L), new UUID(0L, 10L))
      );
      Assertions.assertEquals(
         List.of(new UUID(0L, 9L), new UUID(0L, 10L)), grouped.expectedItemEntityIds()
      );
      Assertions.assertTrue(grouped.expectedItemEntityId().isEmpty());
      Assertions.assertEquals(4, new WorldInteractionActionSpec.PickupWait(
         40, List.of(
            new UUID(0L, 9L), new UUID(0L, 10L), new UUID(0L, 11L),
            new UUID(0L, 12L)
         )
      ).expectedItemEntityIds().size());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.PickupWait(0, Optional.empty()));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.PickupWait(1, Optional.of(new UUID(0L, 0L))));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.PickupWait(
         1, List.of(new UUID(0L, 1L), new UUID(0L, 1L))
      ));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.PickupWait(
         1, List.of(
            new UUID(0L, 1L), new UUID(0L, 2L), new UUID(0L, 3L),
            new UUID(0L, 4L), new UUID(0L, 5L)
         )
      ));
   }

   private static InventoryMenuSnapshot nativeInventorySnapshot() {
      List<ItemStackFingerprint> slots = new ArrayList<>();
      for (int index = 0; index < 41; index++) {
         slots.add(ItemStackFingerprint.empty());
      }
      slots.set(0, STICK);
      return new InventoryMenuSnapshot(
         0, 0, 0, ItemStackFingerprint.empty(), slots
      );
   }

   @Test
   void attackOwnsMainHandAndCannotSelfDeclareCooldownPolicy() {
      EntityTargetFingerprint var1 = entityTarget();
      WorldInteractionActionSpec.AttackEntity var2 = new WorldInteractionActionSpec.AttackEntity(var1);
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.ATTACK_ENTITY, var2.kind());
      Assertions.assertEquals(Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT), var2.channels());
      Assertions.assertThrows(NullPointerException.class, () -> new WorldInteractionActionSpec.AttackEntity(null));
   }

   @Test
   void blockActionsCarryHitStateAndHeldItemPreconditions() {
      BlockHitTarget var1 = blockTarget();
      WorldInteractionActionSpec.UseOnBlock var2 = new WorldInteractionActionSpec.UseOnBlock(WorldInteractionActionSpec.Hand.OFF_HAND, var1, STICK);
      WorldInteractionActionSpec.BreakBlock var3 = new WorldInteractionActionSpec.BreakBlock(var1, STICK);
      BlockTargetFingerprint below = blockTargetAt(0, 63, 0, "minecraft:sugar_cane");
      BlockTargetFingerprint above = blockTargetAt(0, 65, 0, "minecraft:air");
      WorldInteractionActionSpec.BreakBlock guarded = new WorldInteractionActionSpec.BreakBlock(
         var1, STICK, List.of(below, above)
      );
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.USE_ON_BLOCK, var2.kind());
      Assertions.assertEquals(Set.of(ActionChannel.OFF_HAND, ActionChannel.INTERACT), var2.channels());
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.BREAK_BLOCK, var3.kind());
      Assertions.assertEquals(Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT), var3.channels());
      Assertions.assertTrue(var3.neighborPreconditions().isEmpty());
      Assertions.assertEquals(List.of(below, above), guarded.neighborPreconditions());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.BreakBlock(
         var1, STICK, List.of(blockTargetAt(1, 65, 0, "minecraft:air"))
      ));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.BreakBlock(
         var1, STICK, List.of(below, below)
      ));
   }

   @Test
   void placeBlockFixesMainHandAndCarriesTheExactAdjacentPlacedState() {
      BlockHitTarget anchor = blockTarget();
      BlockTargetFingerprint expectedPlaced = blockTargetAt(0, 65, 0, "minecraft:oak_planks");
      WorldInteractionActionSpec.PlaceBlock action = new WorldInteractionActionSpec.PlaceBlock(anchor, expectedPlaced, OAK_PLANKS);

      Assertions.assertAll(
         () -> Assertions.assertEquals(WorldInteractionActionSpec.Kind.PLACE_BLOCK, action.kind()),
         () -> Assertions.assertEquals(ActionKind.PLACE_BLOCK, new WorldInteractionAction(action).kind()),
         () -> Assertions.assertEquals(
            Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT, ActionChannel.LOOK),
            action.channels()
         ),
         () -> Assertions.assertEquals(anchor, action.anchor()),
         () -> Assertions.assertEquals(expectedPlaced, action.expectedPlaced()),
         () -> Assertions.assertEquals(OAK_PLANKS, action.expectedHeldItem())
      );
      Assertions.assertThrows(UnsupportedOperationException.class, () -> action.channels().remove(ActionChannel.MAIN_HAND));
   }

   @Test
   void placeBlockRequiresOneExactTargetOnTheAnchorFace() {
      BlockTargetFingerprint anchorTarget = blockTargetAt(0, 64, 0, "minecraft:stone");
      for (BlockHitTarget.Face face : BlockHitTarget.Face.values()) {
         BlockHitTarget anchor = new BlockHitTarget(anchorTarget, face, 0.5, 0.5, 0.5, false);
         BlockCoordinates placed = coordinateOnFace(anchorTarget.position(), face);
         BlockTargetFingerprint expectedPlaced = blockTargetAt(placed.x(), placed.y(), placed.z(), "minecraft:oak_planks");
         Assertions.assertDoesNotThrow(() -> new WorldInteractionActionSpec.PlaceBlock(anchor, expectedPlaced, OAK_PLANKS));
         BlockHitTarget.Face differentFace = face == BlockHitTarget.Face.UP ? BlockHitTarget.Face.DOWN : BlockHitTarget.Face.UP;
         BlockCoordinates wrongPlaced = coordinateOnFace(anchorTarget.position(), differentFace);
         Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new WorldInteractionActionSpec.PlaceBlock(
                  anchor,
                  blockTargetAt(wrongPlaced.x(), wrongPlaced.y(), wrongPlaced.z(), "minecraft:oak_planks"),
                  OAK_PLANKS
               )
         );
      }

      BlockHitTarget anchor = blockTarget();
      BlockTargetFingerprint expectedPlaced = blockTargetAt(0, 65, 0, "minecraft:oak_planks");
      BlockTargetFingerprint wrongDimension = new BlockTargetFingerprint(
         new ResourceId("minecraft:the_nether"), expectedPlaced.position(), expectedPlaced.state()
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.PlaceBlock(anchor, expectedPlaced, EMPTY)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.PlaceBlock(anchor, wrongDimension, OAK_PLANKS)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.PlaceBlock(null, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.PlaceBlock(anchor, null, OAK_PLANKS)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.PlaceBlock(anchor, expectedPlaced, null)
      );
   }

   @Test
   void aimAndPlaceBlockFreezesExactAirTargetAndOneAtomicLease() {
      BlockHitTarget anchor = blockTarget();
      BlockTargetFingerprint targetBefore = blockTargetAt(0, 65, 0, "minecraft:air");
      BlockTargetFingerprint expectedPlaced = blockTargetAt(0, 65, 0, "minecraft:oak_planks");
      WorldInteractionActionSpec.AimAndPlaceBlock action = new WorldInteractionActionSpec.AimAndPlaceBlock(
         anchor, targetBefore, expectedPlaced, OAK_PLANKS
      );

      Assertions.assertAll(
         () -> Assertions.assertEquals(2, WorldInteractionActionSpec.SCHEMA_VERSION),
         () -> Assertions.assertEquals(1, WorldInteractionActionSpec.AimAndPlaceBlock.SCHEMA_VERSION),
         () -> Assertions.assertEquals(WorldInteractionActionSpec.Kind.AIM_AND_PLACE_BLOCK, action.kind()),
         () -> Assertions.assertEquals(ActionKind.AIM_AND_PLACE_BLOCK, new WorldInteractionAction(action).kind()),
         () -> Assertions.assertEquals(
            Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT, ActionChannel.LOOK),
            action.channels()
         ),
         () -> Assertions.assertEquals(anchor, action.anchor()),
         () -> Assertions.assertEquals(targetBefore, action.targetBefore()),
         () -> Assertions.assertEquals(expectedPlaced, action.expectedPlaced()),
         () -> Assertions.assertEquals(OAK_PLANKS, action.expectedHeldItem())
      );
      Assertions.assertThrows(UnsupportedOperationException.class, () -> action.channels().remove(ActionChannel.LOOK));
   }

   @Test
   void aimAndPlaceBlockRequiresExactAirAndGeometricallyCoherentHit() {
      BlockTargetFingerprint anchorTarget = blockTargetAt(0, 64, 0, "minecraft:stone");
      for (BlockHitTarget.Face face : BlockHitTarget.Face.values()) {
         BlockHitTarget anchor = blockHitOnFace(anchorTarget, face, false);
         BlockCoordinates placedPosition = coordinateOnFace(anchorTarget.position(), face);
         BlockTargetFingerprint targetBefore = blockTargetAt(
            placedPosition.x(), placedPosition.y(), placedPosition.z(), "minecraft:air"
         );
         BlockTargetFingerprint expectedPlaced = blockTargetAt(
            placedPosition.x(), placedPosition.y(), placedPosition.z(), "minecraft:oak_planks"
         );
         Assertions.assertDoesNotThrow(() -> new WorldInteractionActionSpec.AimAndPlaceBlock(
            anchor, targetBefore, expectedPlaced, OAK_PLANKS
         ));
      }

      BlockHitTarget anchor = blockTarget();
      BlockTargetFingerprint targetBefore = blockTargetAt(0, 65, 0, "minecraft:air");
      BlockTargetFingerprint expectedPlaced = blockTargetAt(0, 65, 0, "minecraft:oak_planks");
      BlockTargetFingerprint airWithProperties = new BlockTargetFingerprint(
         OVERWORLD,
         targetBefore.position(),
         new BlockStateFingerprint(new ResourceId("minecraft:air"), Map.of("unexpected", "true"))
      );
      BlockTargetFingerprint occupied = blockTargetAt(0, 65, 0, "minecraft:cave_air");
      BlockTargetFingerprint wrongPosition = blockTargetAt(1, 65, 0, "minecraft:air");
      BlockTargetFingerprint wrongDimension = new BlockTargetFingerprint(
         new ResourceId("minecraft:the_nether"), targetBefore.position(), targetBefore.state()
      );
      BlockHitTarget insideAnchor = new BlockHitTarget(
         anchor.target(), BlockHitTarget.Face.UP, 0.5, 1.0, 0.5, true
      );
      BlockHitTarget offFaceAnchor = new BlockHitTarget(
         anchor.target(), BlockHitTarget.Face.UP, 0.5, 0.75, 0.5, false
      );

      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, occupied, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, airWithProperties, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, wrongPosition, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, wrongDimension, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, targetBefore, targetBefore, OAK_PLANKS)
      );
      for (String airBlockId : List.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air")) {
         BlockTargetFingerprint expectedAir = blockTargetAt(0, 65, 0, airBlockId);
         Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> new WorldInteractionActionSpec.AimAndPlaceBlock(
               anchor, targetBefore, expectedAir, OAK_PLANKS
            ),
            "atomic placement must never report a vanilla air state as placed: " + airBlockId
         );
      }
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(insideAnchor, targetBefore, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(offFaceAnchor, targetBefore, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         IllegalArgumentException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, targetBefore, expectedPlaced, EMPTY)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(null, targetBefore, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, null, expectedPlaced, OAK_PLANKS)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, targetBefore, null, OAK_PLANKS)
      );
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.AimAndPlaceBlock(anchor, targetBefore, expectedPlaced, null)
      );
   }

   @Test
   void entityInteractionDistinguishesGenericAndSpecificPaths() {
      EntityTargetFingerprint var1 = entityTarget();
      WorldInteractionActionSpec.InteractEntity var2 = new WorldInteractionActionSpec.InteractEntity(
         WorldInteractionActionSpec.Hand.MAIN_HAND, var1, Optional.empty()
      );
      WorldInteractionActionSpec.InteractEntity var3 = new WorldInteractionActionSpec.InteractEntity(
         WorldInteractionActionSpec.Hand.MAIN_HAND, var1, Optional.of(new EntityLocalHit(0.0, 1.0, 0.0))
      );
      WorldInteractionActionSpec.InteractEntity var4 = new WorldInteractionActionSpec.InteractEntity(
         WorldInteractionActionSpec.Hand.MAIN_HAND, var1, Optional.empty(), STICK
      );
      Assertions.assertFalse(var2.usesSpecificInteraction());
      Assertions.assertTrue(var3.usesSpecificInteraction());
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.INTERACT_ENTITY, var3.kind());
      Assertions.assertTrue(var2.expectedHeldItem().isEmpty());
      Assertions.assertEquals(Optional.of(STICK), var4.expectedHeldItem());
      Assertions.assertThrows(
         NullPointerException.class,
         () -> new WorldInteractionActionSpec.InteractEntity(
               WorldInteractionActionSpec.Hand.MAIN_HAND, var1, Optional.empty(), (ItemStackFingerprint)null
            )
      );
   }

   private static BlockHitTarget blockTarget() {
      BlockTargetFingerprint var1 = blockTargetAt(0, 64, 0, "minecraft:stone");
      return new BlockHitTarget(var1, BlockHitTarget.Face.UP, 0.5, 1.0, 0.5, false);
   }

   private static BlockTargetFingerprint blockTargetAt(int x, int y, int z, String blockId) {
      BlockStateFingerprint state = new BlockStateFingerprint(new ResourceId(blockId), Map.of());
      return new BlockTargetFingerprint(OVERWORLD, new BlockCoordinates(x, y, z), state);
   }

   private static BlockCoordinates coordinateOnFace(BlockCoordinates anchor, BlockHitTarget.Face face) {
      return switch (face) {
         case DOWN -> new BlockCoordinates(anchor.x(), anchor.y() - 1, anchor.z());
         case UP -> new BlockCoordinates(anchor.x(), anchor.y() + 1, anchor.z());
         case NORTH -> new BlockCoordinates(anchor.x(), anchor.y(), anchor.z() - 1);
         case SOUTH -> new BlockCoordinates(anchor.x(), anchor.y(), anchor.z() + 1);
         case WEST -> new BlockCoordinates(anchor.x() - 1, anchor.y(), anchor.z());
         case EAST -> new BlockCoordinates(anchor.x() + 1, anchor.y(), anchor.z());
      };
   }

   private static BlockHitTarget blockHitOnFace(
      BlockTargetFingerprint target, BlockHitTarget.Face face, boolean inside
   ) {
      return switch (face) {
         case DOWN -> new BlockHitTarget(target, face, 0.5, 0.0, 0.5, inside);
         case UP -> new BlockHitTarget(target, face, 0.5, 1.0, 0.5, inside);
         case NORTH -> new BlockHitTarget(target, face, 0.5, 0.5, 0.0, inside);
         case SOUTH -> new BlockHitTarget(target, face, 0.5, 0.5, 1.0, inside);
         case WEST -> new BlockHitTarget(target, face, 0.0, 0.5, 0.5, inside);
         case EAST -> new BlockHitTarget(target, face, 1.0, 0.5, 0.5, inside);
      };
   }

   private static EntityTargetFingerprint entityTarget() {
      return new EntityTargetFingerprint(OVERWORLD, new UUID(0L, 7L), new ResourceId("minecraft:zombie"));
   }
}
