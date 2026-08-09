package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
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
   void releaseDropAndPickupRejectAmbiguousEmptyOrUnboundedRequests() {
      Assertions.assertEquals(
         WorldInteractionActionSpec.Kind.RELEASE_USE, new WorldInteractionActionSpec.ReleaseUse(WorldInteractionActionSpec.Hand.MAIN_HAND, STICK).kind()
      );
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.ReleaseUse(WorldInteractionActionSpec.Hand.MAIN_HAND, EMPTY));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.DropSelected(false, EMPTY));
      WorldInteractionActionSpec.PickupWait var1 = new WorldInteractionActionSpec.PickupWait(40, Optional.of(new UUID(0L, 9L)));
      Assertions.assertEquals(Set.of(ActionChannel.INVENTORY), var1.channels());
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.PickupWait(0, Optional.empty()));
      Assertions.assertThrows(IllegalArgumentException.class, () -> new WorldInteractionActionSpec.PickupWait(1, Optional.of(new UUID(0L, 0L))));
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
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.USE_ON_BLOCK, var2.kind());
      Assertions.assertEquals(Set.of(ActionChannel.OFF_HAND, ActionChannel.INTERACT), var2.channels());
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.BREAK_BLOCK, var3.kind());
      Assertions.assertEquals(Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT), var3.channels());
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
   void entityInteractionDistinguishesGenericAndSpecificPaths() {
      EntityTargetFingerprint var1 = entityTarget();
      WorldInteractionActionSpec.InteractEntity var2 = new WorldInteractionActionSpec.InteractEntity(
         WorldInteractionActionSpec.Hand.MAIN_HAND, var1, Optional.empty()
      );
      WorldInteractionActionSpec.InteractEntity var3 = new WorldInteractionActionSpec.InteractEntity(
         WorldInteractionActionSpec.Hand.MAIN_HAND, var1, Optional.of(new EntityLocalHit(0.0, 1.0, 0.0))
      );
      Assertions.assertFalse(var2.usesSpecificInteraction());
      Assertions.assertTrue(var3.usesSpecificInteraction());
      Assertions.assertEquals(WorldInteractionActionSpec.Kind.INTERACT_ENTITY, var3.kind());
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

   private static EntityTargetFingerprint entityTarget() {
      return new EntityTargetFingerprint(OVERWORLD, new UUID(0L, 7L), new ResourceId("minecraft:zombie"));
   }
}
