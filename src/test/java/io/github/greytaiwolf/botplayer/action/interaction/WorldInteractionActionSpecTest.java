package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
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
      BlockStateFingerprint var0 = new BlockStateFingerprint(new ResourceId("minecraft:stone"), Map.of());
      BlockTargetFingerprint var1 = new BlockTargetFingerprint(OVERWORLD, new BlockCoordinates(0, 64, 0), var0);
      return new BlockHitTarget(var1, BlockHitTarget.Face.UP, 0.5, 1.0, 0.5, false);
   }

   private static EntityTargetFingerprint entityTarget() {
      return new EntityTargetFingerprint(OVERWORLD, new UUID(0L, 7L), new ResourceId("minecraft:zombie"));
   }
}
