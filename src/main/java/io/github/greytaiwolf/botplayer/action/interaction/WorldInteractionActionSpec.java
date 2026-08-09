package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlan;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuSlotRole;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplate;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public sealed interface WorldInteractionActionSpec
   permits WorldInteractionActionSpec.SelectHotbar,
   WorldInteractionActionSpec.SwapInventoryHotbar,
   WorldInteractionActionSpec.InventoryMenuSwap,
   WorldInteractionActionSpec.WorldMenuTransaction,
   WorldInteractionActionSpec.WorldMenuTransfer,
   WorldInteractionActionSpec.WorldMenuRecipe,
   WorldInteractionActionSpec.UseItem,
   WorldInteractionActionSpec.ReleaseUse,
   WorldInteractionActionSpec.UseOnBlock,
   WorldInteractionActionSpec.PlaceBlock,
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
      WORLD_MENU_TRANSACTION,
      WORLD_MENU_TRANSFER,
      WORLD_MENU_RECIPE,
      USE_ITEM,
      RELEASE_USE,
      USE_ON_BLOCK,
      PLACE_BLOCK,
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

   /**
    * 在同一动作内以原版右键打开（或复用 native 2×2 背包菜单）、严格核验并关闭
    * 一个 P5A 白名单 menu。模板不含 window id，实际会话身份只在打开后绑定。
    */
   public static record WorldMenuTransaction(
      WorldInteractionActionSpec.Hand hand,
      Optional<BlockHitTarget> opener,
      ItemStackFingerprint expectedHeldItem,
      MenuTransactionTemplate template,
      MenuTransactionLimits limits
   ) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.INVENTORY,
         ActionChannel.MAIN_HAND,
         ActionChannel.OFF_HAND,
         ActionChannel.INTERACT
      );

      public WorldMenuTransaction(
         WorldInteractionActionSpec.Hand hand,
         Optional<BlockHitTarget> opener,
         ItemStackFingerprint expectedHeldItem,
         MenuTransactionTemplate template,
         MenuTransactionLimits limits
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(opener, "opener");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         Objects.requireNonNull(template, "template");
         Objects.requireNonNull(limits, "limits");
         boolean nativeInventory = template.family()
            == MenuFamily.INVENTORY_2X2;
         if (nativeInventory != opener.isEmpty()) {
            throw new IllegalArgumentException(
               "inventory menu must omit opener and world menus must require one"
            );
         }
         this.hand = hand;
         this.opener = opener;
         this.expectedHeldItem = expectedHeldItem;
         this.template = template;
         this.limits = limits;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.WORLD_MENU_TRANSACTION;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }
   }

   /**
    * 在同一动作中打开一个白名单世界菜单、从刚打开的权威完整快照构造一次完整堆叠
    * transfer，并在验证后关闭。与 {@link WorldMenuTransaction} 的区别是模板不能在
    * 打开前预知容器内容；只允许 move-or-swap 这一条严格守恒原语。
    */
   public static record WorldMenuTransfer(
      WorldInteractionActionSpec.Hand hand,
      BlockHitTarget opener,
      ItemStackFingerprint expectedHeldItem,
      MenuFamily family,
      int sourceSlot,
      int targetSlot,
      MenuTransactionLimits limits
   ) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.INVENTORY,
         ActionChannel.MAIN_HAND,
         ActionChannel.OFF_HAND,
         ActionChannel.INTERACT
      );

      public WorldMenuTransfer(
         WorldInteractionActionSpec.Hand hand,
         BlockHitTarget opener,
         ItemStackFingerprint expectedHeldItem,
         MenuFamily family,
         int sourceSlot,
         int targetSlot,
         MenuTransactionLimits limits
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(opener, "opener");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         Objects.requireNonNull(family, "family");
         Objects.requireNonNull(limits, "limits");
         if (family == MenuFamily.INVENTORY_2X2) {
            throw new IllegalArgumentException(
               "world menu transfer cannot target native inventory"
            );
         }
         family.requireSlot(sourceSlot);
         family.requireSlot(targetSlot);
         if (sourceSlot == targetSlot) {
            throw new IllegalArgumentException(
               "world menu transfer source and target must differ"
            );
         }
         if (!transferRoleAllowed(family.roleAt(sourceSlot))
               || !transferRoleAllowed(family.roleAt(targetSlot))) {
            throw new IllegalArgumentException(
               "world menu transfer cannot touch recipe results or crafting inputs"
            );
         }
         this.hand = hand;
         this.opener = opener;
         this.expectedHeldItem = expectedHeldItem;
         this.family = family;
         this.sourceSlot = sourceSlot;
         this.targetSlot = targetSlot;
         this.limits = limits;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.WORLD_MENU_TRANSFER;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }

      private static boolean transferRoleAllowed(MenuSlotRole role) {
         return role != MenuSlotRole.RESULT
            && role != MenuSlotRole.CRAFTING_INPUT;
      }
   }

   /**
    * 在同一动作中执行一份封闭 P5A 配方合同。所有 source、配方 preview、结果领取和关闭
    * 都经过原版 {@code clicked()}；熔炼会在严格投入后关闭窗口、定期重新右键观察并只在
    * 预期结果完整出现后领取。
    */
   public static record WorldMenuRecipe(
      WorldInteractionActionSpec.Hand hand,
      Optional<BlockHitTarget> opener,
      ItemStackFingerprint expectedHeldItem,
      P5ARecipe recipe,
      int batches,
      MenuTransactionLimits limits
   ) implements WorldInteractionActionSpec {
      private static final long MINIMUM_FURNACE_TICKS = 700L;
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.INVENTORY,
         ActionChannel.MAIN_HAND,
         ActionChannel.OFF_HAND,
         ActionChannel.INTERACT
      );

      public WorldMenuRecipe(
         WorldInteractionActionSpec.Hand hand,
         Optional<BlockHitTarget> opener,
         ItemStackFingerprint expectedHeldItem,
         P5ARecipe recipe,
         int batches,
         MenuTransactionLimits limits
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(opener, "opener");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         Objects.requireNonNull(recipe, "recipe");
         Objects.requireNonNull(limits, "limits");
         if (batches < 1 || batches > recipe.maximumBatches()) {
            throw new IllegalArgumentException(
               "recipe batches exceed the reviewed P5A action bound"
            );
         }
         boolean nativeInventory = recipe.family()
            == MenuFamily.INVENTORY_2X2;
         if (nativeInventory != opener.isEmpty()) {
            throw new IllegalArgumentException(
               "inventory recipe must omit opener and world recipes must require one"
            );
         }
         if (recipe.isFurnace()
               && limits.maxTicks() < MINIMUM_FURNACE_TICKS) {
            throw new IllegalArgumentException(
               "furnace recipe requires at least 700 transaction ticks"
            );
         }
         this.hand = hand;
         this.opener = opener;
         this.expectedHeldItem = expectedHeldItem;
         this.recipe = recipe;
         this.batches = batches;
         this.limits = limits;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.WORLD_MENU_RECIPE;
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

   /**
    * 用主手在精确锚点面放置方块，并要求相邻目标落入完整的预期状态。
    */
   public static record PlaceBlock(
      BlockHitTarget anchor, BlockTargetFingerprint expectedPlaced, ItemStackFingerprint expectedHeldItem
   ) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT);

      public PlaceBlock(BlockHitTarget anchor, BlockTargetFingerprint expectedPlaced, ItemStackFingerprint expectedHeldItem) {
         Objects.requireNonNull(anchor, "anchor");
         Objects.requireNonNull(expectedPlaced, "expectedPlaced");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         if (expectedHeldItem.isEmpty()) {
            throw new IllegalArgumentException("block placement requires a non-empty main-hand item");
         }
         if (!anchor.target().dimension().equals(expectedPlaced.dimension())) {
            throw new IllegalArgumentException("placed block must remain in the anchor dimension");
         }
         if (!isPlacedOnAnchorFace(anchor, expectedPlaced)) {
            throw new IllegalArgumentException("placed block must be adjacent to the anchor face");
         }
         this.anchor = anchor;
         this.expectedPlaced = expectedPlaced;
         this.expectedHeldItem = expectedHeldItem;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.PLACE_BLOCK;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }

      private static boolean isPlacedOnAnchorFace(BlockHitTarget anchor, BlockTargetFingerprint expectedPlaced) {
         BlockCoordinates anchorPosition = anchor.target().position();
         long expectedX = anchorPosition.x();
         long expectedY = anchorPosition.y();
         long expectedZ = anchorPosition.z();
         switch (anchor.face()) {
            case DOWN:
               expectedY--;
               break;
            case UP:
               expectedY++;
               break;
            case NORTH:
               expectedZ--;
               break;
            case SOUTH:
               expectedZ++;
               break;
            case WEST:
               expectedX--;
               break;
            case EAST:
               expectedX++;
         }
         BlockCoordinates placedPosition = expectedPlaced.position();
         return placedPosition.x() == expectedX
            && placedPosition.y() == expectedY
            && placedPosition.z() == expectedZ;
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
