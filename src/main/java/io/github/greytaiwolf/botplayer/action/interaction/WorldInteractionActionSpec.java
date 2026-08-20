package io.github.greytaiwolf.botplayer.action.interaction;

import io.github.greytaiwolf.botplayer.action.ActionChannel;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSwapPlan;
import io.github.greytaiwolf.botplayer.action.interaction.menu.P5ARecipe;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.menu.MenuSlotRole;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionTemplate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
   WorldInteractionActionSpec.WorldVillagerTrade,
   WorldInteractionActionSpec.UseItem,
   WorldInteractionActionSpec.ReleaseUse,
   WorldInteractionActionSpec.UseOnBlock,
   WorldInteractionActionSpec.PlaceBlock,
   WorldInteractionActionSpec.BreakBlock,
   WorldInteractionActionSpec.AttackEntity,
   WorldInteractionActionSpec.InteractEntity,
   WorldInteractionActionSpec.DropSelected,
   WorldInteractionActionSpec.PickupWait,
   WorldInteractionActionSpec.AimAndPlaceBlock {
   /** Schema v2 adds an independent strict atomic aim-and-place action. */
   int SCHEMA_VERSION = 2;
   int MAX_HOLD_TICKS = 6000;
   int MAX_PICKUP_WAIT_TICKS = 6000;
   /** A bounded multi-receipt pickup fits the terminal ActionOutcome evidence budget. */
   int MAX_PICKUP_EXPECTED_ITEM_ENTITIES = 4;

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

   /**
    * A bounded break request with the exact source block, held tool, and optional
    * face-adjacent world-state fences.
    *
    * <p>The two-argument constructor deliberately retains the established generic
    * break contract.  Callers whose safety depends on a supporting or exposed
    * neighbour staying unchanged (for example, harvesting only the top segment of
    * a sugar-cane column) may provide up to the six face-adjacent fingerprints.
    * The Minecraft backend checks those fences before dispatch, while mining, and
    * again before it reports the break successful.
    */
   public static record BreakBlock(
      BlockHitTarget target,
      ItemStackFingerprint expectedTool,
      List<BlockTargetFingerprint> neighborPreconditions
   ) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.MAIN_HAND, ActionChannel.INTERACT);

      public static final int MAX_NEIGHBOR_PRECONDITIONS = 6;

      /** Preserves the legacy single-target break contract. */
      public BreakBlock(BlockHitTarget target, ItemStackFingerprint expectedTool) {
         this(target, expectedTool, List.of());
      }

      public BreakBlock {
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(expectedTool, "expectedTool");
         neighborPreconditions = List.copyOf(Objects.requireNonNull(
            neighborPreconditions, "neighborPreconditions"
         ));
         if (neighborPreconditions.size() > MAX_NEIGHBOR_PRECONDITIONS) {
            throw new IllegalArgumentException(
               "break block neighbor preconditions exceed face-adjacent bound"
            );
         }
         for (int index = 0; index < neighborPreconditions.size(); index++) {
            BlockTargetFingerprint neighbor = Objects.requireNonNull(
               neighborPreconditions.get(index), "break block neighbor precondition"
            );
            if (!neighbor.dimension().equals(target.target().dimension())
                  || !isFaceAdjacent(target.target().position(), neighbor.position())) {
               throw new IllegalArgumentException(
                  "break block neighbor must be face-adjacent in the target dimension"
               );
            }
            for (int previous = 0; previous < index; previous++) {
               if (neighbor.position().equals(
                     neighborPreconditions.get(previous).position())) {
                  throw new IllegalArgumentException(
                     "break block neighbor positions must be unique"
                  );
               }
            }
         }
      }

      private static boolean isFaceAdjacent(
         BlockCoordinates target, BlockCoordinates neighbor
      ) {
         long deltaX = Math.abs((long) target.x() - neighbor.x());
         long deltaY = Math.abs((long) target.y() - neighbor.y());
         long deltaZ = Math.abs((long) target.z() - neighbor.z());
         return deltaX + deltaY + deltaZ == 1L;
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

   /**
    * A vanilla entity interaction whose target and (when present) held stack are
    * frozen before it enters the action mailbox.
    *
    * <p>{@link #expectedHeldItem()} is deliberately optional only for source
    * compatibility with pre-P5B generic interactions.  New callers which
    * depend on an item effect must use the four-argument constructor: the
    * backend compares that fingerprint against the declared hand both during
    * validation and again immediately before packet dispatch.  An empty value
    * does not make an item claim and must not be used for an item-consuming
    * workflow.
    */
   public static record InteractEntity(
      WorldInteractionActionSpec.Hand hand,
      EntityTargetFingerprint target,
      Optional<EntityLocalHit> localHit,
      Optional<ItemStackFingerprint> expectedHeldItem
   )
      implements WorldInteractionActionSpec {
      /** Legacy generic interaction without an item precondition. */
      public InteractEntity(
         WorldInteractionActionSpec.Hand hand,
         EntityTargetFingerprint target,
         Optional<EntityLocalHit> localHit
      ) {
         this(hand, target, localHit, Optional.empty());
      }

      /**
       * Strict interaction constructor for an item-dependent vanilla effect.
       */
      public InteractEntity(
         WorldInteractionActionSpec.Hand hand,
         EntityTargetFingerprint target,
         Optional<EntityLocalHit> localHit,
         ItemStackFingerprint expectedHeldItem
      ) {
         this(
            hand,
            target,
            localHit,
            Optional.of(Objects.requireNonNull(expectedHeldItem, "expectedHeldItem"))
         );
      }

      public InteractEntity(
         WorldInteractionActionSpec.Hand hand,
         EntityTargetFingerprint target,
         Optional<EntityLocalHit> localHit,
         Optional<ItemStackFingerprint> expectedHeldItem
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(target, "target");
         Objects.requireNonNull(localHit, "localHit");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         this.hand = hand;
         this.target = target;
         this.localHit = localHit;
         this.expectedHeldItem = expectedHeldItem;
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
      WORLD_VILLAGER_TRADE,
      USE_ITEM,
      RELEASE_USE,
      USE_ON_BLOCK,
      PLACE_BLOCK,
      BREAK_BLOCK,
      ATTACK_ENTITY,
      INTERACT_ENTITY,
      DROP_SELECTED,
      PICKUP_WAIT,
      AIM_AND_PLACE_BLOCK;
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
         if (template.family() == MenuFamily.MERCHANT) {
            throw new IllegalArgumentException(
               "merchant menus require the dedicated vanilla villager trade action"
            );
         }
         boolean nativeInventory = template.family()
            == MenuFamily.INVENTORY_2X2;
         if (nativeInventory != opener.isEmpty()) {
            throw new IllegalArgumentException(
               "inventory menu must omit opener and world menus must require one"
            );
         }
         if (template.family() == MenuFamily.FURNACE) {
            throw new IllegalArgumentException(
               "furnace layouts require a typed WorldMenuRecipe contract"
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
    * 在同一动作中打开一个白名单世界菜单、从刚打开的权威完整快照构造一次 transfer，
    * 并在验证后关闭。与 {@link WorldMenuTransaction} 的区别是模板不能在打开前预知
    * 容器内容；{@code requestedAmount=0} 只保留给旧的完整堆叠 move-or-swap，正数则
    * 表示移入空 target 的精确数量。
    */
   public static record WorldMenuTransfer(
      WorldInteractionActionSpec.Hand hand,
      BlockHitTarget opener,
      ItemStackFingerprint expectedHeldItem,
      MenuFamily family,
      int sourceSlot,
      int targetSlot,
      int requestedAmount,
      MenuTransactionLimits limits
   ) implements WorldInteractionActionSpec {
      /** P5A 单次逐右键 transfer 的审计上限，较大数量应拆成多个明确节点。 */
      public static final int MAXIMUM_EXACT_TRANSFER_AMOUNT = 32;
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
         this(
            hand,
            opener,
            expectedHeldItem,
            family,
            sourceSlot,
            targetSlot,
            0,
            limits
         );
      }

      public WorldMenuTransfer(
         WorldInteractionActionSpec.Hand hand,
         BlockHitTarget opener,
         ItemStackFingerprint expectedHeldItem,
         MenuFamily family,
         int sourceSlot,
         int targetSlot,
         int requestedAmount,
         MenuTransactionLimits limits
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(opener, "opener");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         Objects.requireNonNull(family, "family");
         Objects.requireNonNull(limits, "limits");
         if (family == MenuFamily.INVENTORY_2X2
               || family == MenuFamily.MERCHANT) {
            throw new IllegalArgumentException(
               "world menu transfer cannot target native inventory or merchant"
            );
         }
         family.requireSlot(sourceSlot);
         family.requireSlot(targetSlot);
         if (sourceSlot == targetSlot) {
            throw new IllegalArgumentException(
               "world menu transfer source and target must differ"
            );
         }
         if (requestedAmount < 0
               || requestedAmount > MAXIMUM_EXACT_TRANSFER_AMOUNT) {
            throw new IllegalArgumentException(
               "world menu transfer requested amount exceeds the P5A bound"
            );
         }
         if (requestedAmount > 0
               && limits.maxClicks() < requestedAmount + 2) {
            throw new IllegalArgumentException(
               "world menu transfer limits cannot settle the requested amount"
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
         this.requestedAmount = requestedAmount;
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
            && role != MenuSlotRole.CRAFTING_INPUT
            && role != MenuSlotRole.MERCHANT_PAYMENT
            && role != MenuSlotRole.MERCHANT_RESULT;
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
               && limits.maxTicks()
                  < recipe.furnaceKind().minimumTransactionTicks()) {
            throw new IllegalArgumentException(
               "furnace recipe transaction limit is below its exact furnace-kind minimum"
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

   /**
    * 一次受限的原版村民交易。
    *
    * <p>这不是通用商店或 UI 自动化。它只表达一笔单支付物的 {@code Villager} offer：开始
    * 时主手和 cursor 都为空，玩家 36 格可交易背包中只有指定 output 格为空；适配器会打开
    * 精确 {@code MerchantMenu}，逐右键投入冻结的支付数量，再以一次原版 {@code QUICK_MOVE}
    * 领取结果。这样取消不会把已经领取的结果遗留在 cursor。所有 offer、库存、菜单、实体和
    * generation 都必须在服务端重新验证；交易 XP 也被冻结，并拒绝会跨越村民等级门槛的
    * offer。未知 merchant、双支付 offer、折扣漂移和布局漂移一律失败关闭。
    */
   public static record WorldVillagerTrade(
      WorldInteractionActionSpec.Hand hand,
      EntityTargetFingerprint villager,
      int offerIndex,
      int sourceInventorySlot,
      int outputInventorySlot,
      ItemStackFingerprint expectedSource,
      ItemStackFingerprint expectedCost,
      ItemStackFingerprint expectedResult,
      MerchantOfferState expectedOfferState,
      int expectedVillagerLevel,
      int expectedVillagerXp,
      int expectedOfferUses,
      int expectedOfferMaxUses,
      MenuTransactionLimits limits
   ) implements WorldInteractionActionSpec {
      /** 单次精确支付右键的 P5B 审计上限。 */
      public static final int MAXIMUM_COST_COUNT = 16;
      /** 原版玩家 inventory 的可交易主背包与快捷栏，盔甲/副手不在 MerchantMenu 中。 */
      public static final int FIRST_PLAYER_INVENTORY_SLOT = 0;
      public static final int LAST_PLAYER_INVENTORY_SLOT = 35;
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.INVENTORY,
         ActionChannel.MAIN_HAND,
         ActionChannel.INTERACT
      );

      public WorldVillagerTrade(
         WorldInteractionActionSpec.Hand hand,
         EntityTargetFingerprint villager,
         int offerIndex,
         int sourceInventorySlot,
         int outputInventorySlot,
         ItemStackFingerprint expectedSource,
         ItemStackFingerprint expectedCost,
         ItemStackFingerprint expectedResult,
         MerchantOfferState expectedOfferState,
         int expectedVillagerLevel,
         int expectedVillagerXp,
         int expectedOfferUses,
         int expectedOfferMaxUses,
         MenuTransactionLimits limits
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(villager, "villager");
         Objects.requireNonNull(expectedSource, "expectedSource");
         Objects.requireNonNull(expectedCost, "expectedCost");
         Objects.requireNonNull(expectedResult, "expectedResult");
         Objects.requireNonNull(expectedOfferState, "expectedOfferState");
         Objects.requireNonNull(limits, "limits");
         if (hand != WorldInteractionActionSpec.Hand.MAIN_HAND) {
            throw new IllegalArgumentException(
               "vanilla villager trade only permits the empty main hand"
            );
         }
         if (!"minecraft:villager".equals(villager.entityType().value())) {
            throw new IllegalArgumentException(
               "vanilla villager trade requires an exact minecraft:villager target"
            );
         }
         if (offerIndex < 0
               || sourceInventorySlot < FIRST_PLAYER_INVENTORY_SLOT
               || sourceInventorySlot > LAST_PLAYER_INVENTORY_SLOT
               || outputInventorySlot < FIRST_PLAYER_INVENTORY_SLOT
               || outputInventorySlot > LAST_PLAYER_INVENTORY_SLOT
               || sourceInventorySlot == outputInventorySlot) {
            throw new IllegalArgumentException(
               "vanilla villager trade inventory slots or offer index are invalid"
            );
         }
         if (expectedSource.isEmpty()
               || expectedCost.isEmpty()
               || expectedCost.count() > MAXIMUM_COST_COUNT
               || expectedResult.isEmpty()
               || !expectedSource.sameItemAndComponents(expectedCost)
               || expectedSource.count() <= expectedCost.count()
               || expectedCost.sameItemAndComponents(expectedResult)
               || !expectedOfferState.baseCost()
                     .sameItemAndComponents(expectedCost)) {
            throw new IllegalArgumentException(
               "vanilla villager trade must use a larger exact source, stable base price, and distinct bounded cost/result"
            );
         }
         if (expectedVillagerLevel < 1
               || expectedVillagerLevel > 5
               || expectedVillagerXp < 0
               || expectedOfferUses < 0
               || expectedOfferMaxUses < 1
               || expectedOfferUses >= expectedOfferMaxUses) {
            throw new IllegalArgumentException(
               "vanilla villager trade XP or offer use bounds are invalid"
            );
         }
         if (limits.maxClicks() < expectedCost.count() + 3) {
            throw new IllegalArgumentException(
               "vanilla villager trade click limit cannot complete its bounded payment"
            );
         }
         try {
            Math.addExact(
               expectedVillagerXp,
               expectedOfferState.rewardsExperience()
                  ? expectedOfferState.xp() : 0
            );
         } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
               "vanilla villager trade XP would overflow", exception
            );
         }
         this.hand = hand;
         this.villager = villager;
         this.offerIndex = offerIndex;
         this.sourceInventorySlot = sourceInventorySlot;
         this.outputInventorySlot = outputInventorySlot;
         this.expectedSource = expectedSource;
         this.expectedCost = expectedCost;
         this.expectedResult = expectedResult;
         this.expectedOfferState = expectedOfferState;
         this.expectedVillagerLevel = expectedVillagerLevel;
         this.expectedVillagerXp = expectedVillagerXp;
         this.expectedOfferUses = expectedOfferUses;
         this.expectedOfferMaxUses = expectedOfferMaxUses;
         this.limits = limits;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.WORLD_VILLAGER_TRADE;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }

      /**
       * 交易 offer 的所有价格/经验状态。它与可见的 {@link WorldVillagerTrade#expectedCost()}、结果、uses
       * 和 max uses 一起构成动作提交时的完整原版 offer 冻结，而不是仅按当前 cost 猜测。
       */
      public static record MerchantOfferState(
         ItemStackFingerprint baseCost,
         int demand,
         int specialPriceDiff,
         int priceMultiplierBits,
         int xp,
         boolean rewardsExperience
      ) {
         public MerchantOfferState {
            Objects.requireNonNull(baseCost, "baseCost");
            float priceMultiplier = Float.intBitsToFloat(
               priceMultiplierBits
            );
            if (baseCost.isEmpty()
                  || !Float.isFinite(priceMultiplier)
                  || priceMultiplier < 0.0F
                  || xp < 0) {
               throw new IllegalArgumentException(
                  "merchant offer state must contain a valid base price and non-negative XP"
               );
            }
         }
      }
   }

   /**
    * Waits only for a bounded, frozen set of normal collision-driven item pickups.
    * An empty list retains the generic "some inventory change" contract; a non-empty
    * list requires every exact receipt UUID to disappear and be conserved before success.
    */
   public static record PickupWait(int ticks, List<UUID> expectedItemEntityIds) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> CHANNELS = Set.of(ActionChannel.INVENTORY);

      /** Preserves callers that bind exactly one receipt or no receipt. */
      public PickupWait(int ticks, Optional<UUID> expectedItemEntityId) {
         this(ticks, Objects.requireNonNull(expectedItemEntityId,
               "expectedItemEntityId").map(List::of).orElseGet(List::of));
      }

      public PickupWait {
         if (ticks < 1 || ticks > MAX_PICKUP_WAIT_TICKS) {
            throw new IllegalArgumentException("pickup wait ticks must be between 1 and 6000");
         }
         expectedItemEntityIds = List.copyOf(Objects.requireNonNull(
               expectedItemEntityIds, "expectedItemEntityIds"));
         if (expectedItemEntityIds.size()
               > MAX_PICKUP_EXPECTED_ITEM_ENTITIES) {
            throw new IllegalArgumentException(
                  "pickup wait expected item entities exceed bounded receipt capacity");
         }
         Set<UUID> unique = new LinkedHashSet<>();
         for (UUID entityId : expectedItemEntityIds) {
            InteractionChecks.requireNonZeroUuid(Objects.requireNonNull(
                  entityId, "expectedItemEntityId"), "expectedItemEntityId");
            if (!unique.add(entityId)) {
               throw new IllegalArgumentException(
                     "pickup wait expected item entity ids must be unique");
            }
         }
      }

      /** Legacy single-receipt view; multi-receipt callers must use the full list. */
      public Optional<UUID> expectedItemEntityId() {
         return expectedItemEntityIds.size() == 1
               ? Optional.of(expectedItemEntityIds.get(0))
               : Optional.empty();
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
      /**
       * A reviewed P5A furnace placement can reject before native dispatch when
       * the frozen horizontal-facing context has drifted.  The bounded receipt
       * is deliberately an evidence capability rather than a human-readable
       * summary: only that no-side-effect rejection may request one fresh
       * production replan.
       */
      public static final String PRE_DISPATCH_FACING_DRIFT_EVIDENCE_KEY =
         "placement.pre_dispatch";
      public static final String PRE_DISPATCH_FACING_DRIFT_EVIDENCE_VALUE =
         "facing_drift";

      /*
       * Some vanilla blocks (notably furnaces) derive their complete placed
       * state from the player's facing.  Holding LOOK together with the hand
       * and interaction lease prevents a competing Bot action from changing
       * that placement context after the exact state was frozen.
       */
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.MAIN_HAND,
         ActionChannel.INTERACT,
         ActionChannel.LOOK
      );

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
         if (!WorldInteractionActionSpec.isPlacedOnAnchorFace(anchor, expectedPlaced)) {
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

   }

   /**
    * A single main-hand placement whose look, final revalidation, native packet, and exact
    * verification share one {@code LOOK} lease. This does not change the older {@link PlaceBlock}
    * contract used by P5A workstation placement.
    */
   public static record AimAndPlaceBlock(
      BlockHitTarget anchor,
      BlockTargetFingerprint targetBefore,
      BlockTargetFingerprint expectedPlaced,
      ItemStackFingerprint expectedHeldItem
   ) implements WorldInteractionActionSpec {
      public static final int SCHEMA_VERSION = 1;
      private static final ResourceId AIR = new ResourceId("minecraft:air");
      private static final BlockStateFingerprint AIR_STATE = new BlockStateFingerprint(AIR, Map.of());
      private static final Set<ResourceId> VANILLA_AIR_BLOCK_IDS = Set.of(
         AIR,
         new ResourceId("minecraft:cave_air"),
         new ResourceId("minecraft:void_air")
      );
      private static final Set<ActionChannel> CHANNELS = Set.of(
         ActionChannel.MAIN_HAND,
         ActionChannel.INTERACT,
         ActionChannel.LOOK
      );

      public AimAndPlaceBlock(
         BlockHitTarget anchor,
         BlockTargetFingerprint targetBefore,
         BlockTargetFingerprint expectedPlaced,
         ItemStackFingerprint expectedHeldItem
      ) {
         Objects.requireNonNull(anchor, "anchor");
         Objects.requireNonNull(targetBefore, "targetBefore");
         Objects.requireNonNull(expectedPlaced, "expectedPlaced");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         if (expectedHeldItem.isEmpty()) {
            throw new IllegalArgumentException("atomic block placement requires a non-empty main-hand item");
         }
         if (!AIR_STATE.equals(targetBefore.state())) {
            throw new IllegalArgumentException("atomic block placement targetBefore must be exact minecraft:air{}");
         }
         if (VANILLA_AIR_BLOCK_IDS.contains(expectedPlaced.state().blockId())) {
            throw new IllegalArgumentException("atomic block placement expected state must not be vanilla air");
         }
         if (!anchor.target().dimension().equals(targetBefore.dimension())
               || !targetBefore.dimension().equals(expectedPlaced.dimension())) {
            throw new IllegalArgumentException("atomic block placement facts must share the anchor dimension");
         }
         if (!targetBefore.position().equals(expectedPlaced.position())) {
            throw new IllegalArgumentException("atomic block placement targetBefore and expected position must match");
         }
         if (!WorldInteractionActionSpec.isPlacedOnAnchorFace(anchor, expectedPlaced)) {
            throw new IllegalArgumentException("atomic block placement must target the declared anchor face");
         }
         if (anchor.inside() || !isHitOnDeclaredFace(anchor)) {
            throw new IllegalArgumentException("atomic block placement hit must lie exactly on the declared anchor face");
         }
         this.anchor = anchor;
         this.targetBefore = targetBefore;
         this.expectedPlaced = expectedPlaced;
         this.expectedHeldItem = expectedHeldItem;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.AIM_AND_PLACE_BLOCK;
      }

      @Override
      public Set<ActionChannel> channels() {
         return CHANNELS;
      }

      private static boolean isHitOnDeclaredFace(BlockHitTarget anchor) {
         return switch (anchor.face()) {
            case DOWN -> anchor.localY() == 0.0D;
            case UP -> anchor.localY() == 1.0D;
            case NORTH -> anchor.localZ() == 0.0D;
            case SOUTH -> anchor.localZ() == 1.0D;
            case WEST -> anchor.localX() == 0.0D;
            case EAST -> anchor.localX() == 1.0D;
         };
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
      WorldInteractionActionSpec.Hand hand,
      ItemStackFingerprint expectedHeldItem,
      WorldInteractionActionSpec.ItemUseMode mode,
      int holdTicks,
      Optional<UseItemPreconditions> strictPreconditions
   ) implements WorldInteractionActionSpec {
      private static final Set<ActionChannel> STRICT_MAIN_HAND_CHANNELS = Set.of(
         ActionChannel.MAIN_HAND, ActionChannel.INTERACT, ActionChannel.INVENTORY
      );
      private static final Set<ActionChannel> STRICT_OFF_HAND_CHANNELS = Set.of(
         ActionChannel.OFF_HAND, ActionChannel.INTERACT, ActionChannel.INVENTORY
      );

      /**
       * Backwards-compatible generic use without menu/effect dispatch fences.
       */
      public UseItem(
         WorldInteractionActionSpec.Hand hand,
         ItemStackFingerprint expectedHeldItem,
         WorldInteractionActionSpec.ItemUseMode mode,
         int holdTicks
      ) {
         this(hand, expectedHeldItem, mode, holdTicks, Optional.empty());
      }

      /**
       * Strict native use with an immutable menu/effect snapshot.
       */
      public UseItem(
         WorldInteractionActionSpec.Hand hand,
         ItemStackFingerprint expectedHeldItem,
         WorldInteractionActionSpec.ItemUseMode mode,
         int holdTicks,
         UseItemPreconditions strictPreconditions
      ) {
         this(
            hand,
            expectedHeldItem,
            mode,
            holdTicks,
            Optional.of(Objects.requireNonNull(
               strictPreconditions, "strictPreconditions"
            ))
         );
      }

      public UseItem(
         WorldInteractionActionSpec.Hand hand,
         ItemStackFingerprint expectedHeldItem,
         WorldInteractionActionSpec.ItemUseMode mode,
         int holdTicks,
         Optional<UseItemPreconditions> strictPreconditions
      ) {
         Objects.requireNonNull(hand, "hand");
         Objects.requireNonNull(expectedHeldItem, "expectedHeldItem");
         Objects.requireNonNull(mode, "mode");
         Objects.requireNonNull(strictPreconditions, "strictPreconditions");
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
         this.strictPreconditions = strictPreconditions;
      }

      @Override
      public WorldInteractionActionSpec.Kind kind() {
         return WorldInteractionActionSpec.Kind.USE_ITEM;
      }

      @Override
      public Set<ActionChannel> channels() {
         if (this.strictPreconditions.isEmpty()) {
            return this.hand.interactionChannels();
         }
         return switch (this.hand) {
            case MAIN_HAND -> STRICT_MAIN_HAND_CHANNELS;
            case OFF_HAND -> STRICT_OFF_HAND_CHANNELS;
         };
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

   private static boolean isPlacedOnAnchorFace(
      BlockHitTarget anchor, BlockTargetFingerprint expectedPlaced
   ) {
      BlockCoordinates anchorPosition = anchor.target().position();
      long expectedX = anchorPosition.x();
      long expectedY = anchorPosition.y();
      long expectedZ = anchorPosition.z();
      switch (anchor.face()) {
         case DOWN -> expectedY--;
         case UP -> expectedY++;
         case NORTH -> expectedZ--;
         case SOUTH -> expectedZ++;
         case WEST -> expectedX--;
         case EAST -> expectedX++;
      }
      BlockCoordinates placedPosition = expectedPlaced.position();
      return placedPosition.x() == expectedX
         && placedPosition.y() == expectedY
         && placedPosition.z() == expectedZ;
   }
}
