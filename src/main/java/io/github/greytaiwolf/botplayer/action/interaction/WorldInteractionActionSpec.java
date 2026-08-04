package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlan;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public sealed interface WorldInteractionActionSpec
   permits WorldInteractionActionSpec.SelectHotbar,
   WorldInteractionActionSpec.SwapInventoryHotbar,
   WorldInteractionActionSpec.InventoryMenuSwap,
   WorldInteractionActionSpec.UseItem,
   WorldInteractionActionSpec.ReleaseUse,
   WorldInteractionActionSpec.UseOnBlock,
   WorldInteractionActionSpec.BreakBlock,
   WorldInteractionActionSpec.AttackEntity,
   WorldInteractionActionSpec.InteractEntity,
   WorldInteractionActionSpec.DropSelected,
   WorldInteractionActionSpec.PickupWait {
   int SCHEMA_VERSION = 1;
   int MAX_HOLD_TICKS = 6000;
   int MAX_PICKUP_WAIT_TICKS = 6000;

   WorldInteractionActionSpec.Kind kind();

   Set<ActionChannel> channels();

   public static record AttackEntity(EntityTargetFingerprint target) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT);

      public AttackEntity(EntityTargetFingerprint target) {
         Objects.requireNonNull(target, "target");
         this.target = target;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.ATTACK_ENTITY;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static record BreakBlock(BlockHitTarget target, ItemStackFingerprint expectedTool) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT);

      public BreakBlock(BlockHitTarget target, ItemStackFingerprint expectedTool) {
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(expectedTool, "expectedTool");
         this.target = target;
         this.expectedTool = expectedTool;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.BREAK_BLOCK;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static record DropSelected(boolean entireStack, ItemStackFingerprint expectedSelected) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MAIN_HAND, ActionChannel.INVENTORY);

      public DropSelected(boolean entireStack, ItemStackFingerprint expectedSelected) {
         Objects.requireNonNull(expectedSelected, "expectedSelected");
         if (expectedSelected.isEmpty()) {
            throw new IllegalArgumentException("drop requires a non-empty selected item");
         } else {
            this.entireStack = entireStack;
            this.expectedSelected = expectedSelected;
         }
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.DROP_SELECTED;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static enum Hand {
      MAIN_HAND(Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT)),
      OFF_HAND(Set.of(ActionChannel.OFF_HAND, ActionChannel.INTERACT));

      private final Set<ActionChannel> interactionChannels;

      private Hand(Set<ActionChannel> nullxx) {
         this.interactionChannels = nullxx;
      }

      public Set<ActionChannel> interactionChannels() {
         return this.interactionChannels;
      }
   }

   public static record InteractEntity(WorldInteractionActionSpec.Hand hand, EntityTargetFingerprint target, Optional<EntityLocalHit> localHit)
      implements WorldInteractionActionSpec {
      public InteractEntity(WorldInteractionActionSpec.Hand hand, EntityTargetFingerprint target, Optional<EntityLocalHit> localHit) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(localHit, "localHit");
         this.hand = hand;
         this.target = target;
         this.localHit = localHit;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.INTERACT_ENTITY;
      }

      @Override
      public Set<ActionChannel> channels() {
         return this.hand.interactionChannels();
      }

      public boolean usesSpecificInteraction() {
         return this.localHit.isPresent();
      }
   }

   public static enum ItemUseMode {
      INSTANT,
      RELEASE_AFTER_HOLD,
      FINISH_NATURALLY;
   }

   public static enum Kind {
      SELECT_HOTBAR,
      SWAP_INVENTORY_HOTBAR,
      INVENTORY_MENU_SWAP,
      USE_ITEM,
      RELEASE_USE,
      USE_ON_BLOCK,
      BREAK_BLOCK,
      ATTACK_ENTITY,
      INTERACT_ENTITY,
      DROP_SELECTED,
      PICKUP_WAIT;
   }

   /**
    * 在原生玩家 InventoryMenu 中执行一份有界、完整快照约束的 SWAP 计划。
    */
   public static record InventoryMenuSwap(InventoryMenuSwapPlan plan)
      implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.INVENTORY,
         ActionChannel.MAIN_HAND,
         ActionChannel.OFF_HAND
      );

      public InventoryMenuSwap(InventoryMenuSwapPlan plan) {
         Objects.requireNonNull(plan, "plan");
         this.plan = plan;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.INVENTORY_MENU_SWAP;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static record PickupWait(int ticks, Optional<UUID> expectedItemEntityId) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.INVENTORY);

      public PickupWait(int ticks, Optional<UUID> expectedItemEntityId) {
         if (ticks >= 1 && ticks <= 6000) {
            Objects.requireNonNull(expectedItemEntityId, "expectedItemEntityId");
            expectedItemEntityId.ifPresent(var0 -> InteractionChecks.requireNonZeroUuid(var0, "expectedItemEntityId"));
            this.ticks = ticks;
            this.expectedItemEntityId = expectedItemEntityId;
         } else {
            throw new IllegalArgumentException("pickup wait ticks must be between 1 and 6000");
         }
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.PICKUP_WAIT;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static record ReleaseUse(WorldInteractionActionSpec.Hand hand, ItemStackFingerprint expectedUseItem) implements WorldInteractionActionSpec {
      public ReleaseUse(WorldInteractionActionSpec.Hand hand, ItemStackFingerprint expectedUseItem) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(expectedUseItem, "expectedUseItem");
         if (expectedUseItem.isEmpty()) {
            throw new IllegalArgumentException("release use requires a non-empty item");
         } else {
            this.hand = hand;
            this.expectedUseItem = expectedUseItem;
         }
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.RELEASE_USE;
      }

      @Override
      public Set<ActionChannel> channels() {
         return this.hand.interactionChannels();
      }
   }

   public static record SelectHotbar(int slot, ItemStackFingerprint expectedSlotItem) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.INVENTORY, ActionChannel.MAIN_HAND);

      public SelectHotbar(int slot, ItemStackFingerprint expectedSlotItem) {
         if (slot >= 0 && slot <= 8) {
            Objects.requireNonNull(expectedSlotItem, "expectedSlotItem");
            this.slot = slot;
            this.expectedSlotItem = expectedSlotItem;
         } else {
            throw new IllegalArgumentException("hotbar slot must be between 0 and 8");
         }
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.SELECT_HOTBAR;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static record SwapInventoryHotbar(
      int sourceInventorySlot, int targetHotbarSlot, ItemStackFingerprint expectedSource, ItemStackFingerprint expectedTarget
   ) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.INVENTORY, ActionChannel.MAIN_HAND);

      public SwapInventoryHotbar(
         int sourceInventorySlot, int targetHotbarSlot, ItemStackFingerprint expectedSource, ItemStackFingerprint expectedTarget
      ) {
         if (sourceInventorySlot < 9 || sourceInventorySlot > 35) {
            throw new IllegalArgumentException("source inventory slot must be between 9 and 35");
         }
         if (targetHotbarSlot < 0 || targetHotbarSlot > 8) {
            throw new IllegalArgumentException("target hotbar slot must be between 0 and 8");
         }
         Objects.requireNonNull(expectedSource, "expectedSource");
         Objects.requireNonNull(expectedTarget, "expectedTarget");
         if (expectedSource.equals(expectedTarget)) {
            throw new IllegalArgumentException(
               "source and target fingerprints must differ for an observable swap"
            );
         }
         this.sourceInventorySlot = sourceInventorySlot;
         this.targetHotbarSlot = targetHotbarSlot;
         this.expectedSource = expectedSource;
         this.expectedTarget = expectedTarget;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.SWAP_INVENTORY_HOTBAR;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   public static record UseItem(
      WorldInteractionActionSpec.Hand hand, ItemStackFingerprint expectedHeldItem, WorldInteractionActionSpec.ItemUseMode mode, int holdTicks
   ) implements WorldInteractionActionSpec {
      public UseItem(WorldInteractionActionSpec.Hand hand, ItemStackFingerprint expectedHeldItem, WorldInteractionActionSpec.ItemUseMode mode, int holdTicks) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         Objects.requireNonNull(mode, "mode");
         switch (mode) {
            case INSTANT:
            case FINISH_NATURALLY:
               if (holdTicks != 0) {
                  throw new IllegalArgumentException(mode + " use must have zero holdTicks");
               }
               break;
            case RELEASE_AFTER_HOLD:
               if (holdTicks < 1 || holdTicks > 6000) {
                  throw new IllegalArgumentException("release holdTicks must be between 1 and 6000");
               }
         }

         this.hand = hand;
         this.expectedHeldItem = expectedHeldItem;
         this.mode = mode;
         this.holdTicks = holdTicks;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.USE_ITEM;
      }

      @Override
      public Set<ActionChannel> channels() {
         return this.hand.interactionChannels();
      }
   }

   public static record UseOnBlock(WorldInteractionActionSpec.Hand hand, BlockHitTarget target, ItemStackFingerprint expectedHeldItem)
      implements WorldInteractionActionSpec {
      public UseOnBlock(WorldInteractionActionSpec.Hand hand, BlockHitTarget target, ItemStackFingerprint expectedHeldItem) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         this.hand = hand;
         this.target = target;
         this.expectedHeldItem = expectedHeldItem;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.USE_ON_BLOCK;
      }

      @Override
      public Set<ActionChannel> channels() {
         return this.hand.interactionChannels();
      }
   }
}
