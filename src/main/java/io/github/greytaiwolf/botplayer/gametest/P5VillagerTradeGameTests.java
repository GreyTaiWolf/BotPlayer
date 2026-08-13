package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuTransactionLimits;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.npc.VillagerData;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.MerchantMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.trading.ItemCost;
import net.minecraft.world.item.trading.MerchantOffer;
import net.minecraft.world.item.trading.MerchantOffers;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5B 的真实原版村民交易回归。
 *
 * <p>fixture 可以为了安排输入而直接设置 villager offer 与测试背包，但被测动作绝不调用
 * offer/inventory 写入方法：它只通过实体 interaction、精确 MerchantMenu 和 native clicked
 * 完成。四个场景分别覆盖成功、价格漂移拒绝、等级门槛拒绝和 carried 非空的中途取消返还。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5VillagerTradeGameTests {
    private static final String BATCH = "p5_villager_trade";
    private static final int TIMEOUT_TICKS = 160;
    /*
     * Cancellation deliberately uses the first inventory slot as payment source. Native close
     * returns a carried partial payment through the ordinary inventory free-slot search; source
     * therefore remains the unique earliest landing slot while the selected, empty hotbar output
     * stays reserved for the terminal result QUICK_MOVE.
     */
    private static final int SOURCE_SLOT = 0;
    private static final int OUTPUT_SLOT = 8;
    private static final int COST_COUNT = 5;
    private static final int SOURCE_COUNT = 7;
    private static final BlockPos RELATIVE_VILLAGER =
            new BlockPos(4, 1, 2);

    private P5VillagerTradeGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void singleOfferUsesExactMerchantMenuClicksAndCloses(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "villager_trade_success");
        TestBot bot = fixture.spawn("trade");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Villager villager = spawnVillager(helper, cleanup);
        MerchantOffer offer = offer(villager);
        try {
            prepareStrictInventory(bot);
            CompletionStage<ActionOutcome> completion = P2GameTestSupport.submit(
                    bot, new WorldInteractionAction(action(bot, villager,
                            offer)), 120);
            P2GameTestSupport.awaitOutcome(
                    helper, completion, 120, cleanup, outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Villager trade did not succeed: " + outcome);
                            P2GameTestSupport.require(
                                    hasEvidence(outcome, "menu.family",
                                            "merchant")
                                            && hasEvidence(outcome,
                                                    "menu.clicks",
                                                    Integer.toString(
                                                            COST_COUNT + 3))
                                            && hasEvidence(outcome,
                                                    "merchant.uses_before", "0")
                                            && hasEvidence(outcome,
                                                    "merchant.uses_after", "1")
                                            && hasEvidence(outcome,
                                                    "merchant.xp_before", "0")
                                            && hasEvidence(outcome,
                                                    "merchant.xp_after", "1")
                                            && hasEvidence(outcome,
                                                    "menu.closed", "true"),
                                    "Villager trade lacked exact menu/offer evidence: "
                                            + outcome.evidence());
                            requireNativeEmptyMenu(bot,
                                    "Successful villager trade leaked menu or cursor");
                            P2GameTestSupport.require(
                                    bot.player().getInventory()
                                                    .getItem(SOURCE_SLOT)
                                            .is(Items.WHEAT)
                                            && bot.player().getInventory()
                                                    .getItem(SOURCE_SLOT)
                                                    .getCount()
                                                    == SOURCE_COUNT - COST_COUNT
                                            && bot.player().getInventory()
                                                    .getItem(OUTPUT_SLOT)
                                                    .is(Items.EMERALD)
                                            && bot.player().getInventory()
                                                    .getItem(OUTPUT_SLOT)
                                                    .getCount() == 1
                                            && offer.getUses() == 1
                                            && villager.getVillagerXp() == 1,
                                    "Villager trade did not settle exactly one offer");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void changedOfferPriceFailsBeforeMerchantMenuClicks(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "villager_trade_price_drift");
        TestBot bot = fixture.spawn("trade");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Villager villager = spawnVillager(helper, cleanup);
        MerchantOffer offer = offer(villager);
        try {
            prepareStrictInventory(bot);
            WorldInteractionActionSpec.WorldVillagerTrade frozen = action(
                    bot, villager, offer);
            offer.setSpecialPriceDiff(2);
            P2GameTestSupport.require(
                    !MinecraftActionSnapshot.item(bot.player(), offer.getCostA())
                            .equals(frozen.expectedCost()),
                    "Price-drift fixture did not change the actual vanilla cost");
            CompletionStage<ActionOutcome> completion = P2GameTestSupport.submit(
                    bot, new WorldInteractionAction(frozen), 80);
            P2GameTestSupport.awaitOutcome(
                    helper, completion, 80, cleanup, outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.FAILED
                                            && outcome.failureCode()
                                                    == ActionFailureCode
                                                            .PRECONDITION_FAILED,
                                    "Price-drift trade was not rejected: "
                                            + outcome);
                            requireNativeEmptyMenu(bot,
                                    "Rejected price-drift trade leaked menu or cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.WHEAT)
                                                    == SOURCE_COUNT
                                            && inventoryCount(bot,
                                                    Items.EMERALD) == 0
                                            && offer.getUses() == 0
                                            && villager.getVillagerXp() == 0,
                                    "Price-drift rejection changed inventory or offer uses");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void levelCrossingOfferFailsBeforeMerchantMenuClicks(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "villager_trade_level_boundary");
        TestBot bot = fixture.spawn("trade");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Villager villager = spawnVillager(helper, cleanup);
        MerchantOffer offer = offer(villager);
        try {
            prepareStrictInventory(bot);
            int level = villager.getVillagerData().getLevel();
            int threshold = VillagerData.getMinXpPerLevel(level);
            P2GameTestSupport.require(
                    VillagerData.canLevelUp(level) && threshold > 0,
                    "Villager trade level-boundary fixture is not levelable");
            villager.setVillagerXp(threshold - 1);
            WorldInteractionActionSpec.WorldVillagerTrade frozen = action(
                    bot, villager, offer);
            CompletionStage<ActionOutcome> completion = P2GameTestSupport.submit(
                    bot, new WorldInteractionAction(frozen), 80);
            P2GameTestSupport.awaitOutcome(
                    helper, completion, 80, cleanup, outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.FAILED
                                            && outcome.failureCode()
                                                    == ActionFailureCode
                                                            .PRECONDITION_FAILED,
                                    "Level-crossing trade was not rejected: "
                                            + outcome);
                            requireNativeEmptyMenu(bot,
                                    "Rejected level-crossing trade leaked menu or cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.WHEAT)
                                                    == SOURCE_COUNT
                                            && inventoryCount(bot,
                                                    Items.EMERALD) == 0
                                            && offer.getUses() == 0
                                            && villager.getVillagerXp()
                                                    == threshold - 1,
                                    "Level-crossing rejection changed inventory, offer, or XP");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void cancellationWithCarriedPaymentReturnsToExactInventory(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "villager_trade_cancel");
        TestBot bot = fixture.spawn("trade");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        Villager villager = spawnVillager(helper, cleanup);
        MerchantOffer offer = offer(villager);
        try {
            prepareStrictInventory(bot);
            TrackedTradeAction tracked = submitTracked(bot,
                    new WorldInteractionAction(action(bot, villager, offer)),
                    120, "cancel");
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> bot.player().containerMenu instanceof MerchantMenu
                            && !bot.player().containerMenu.getCarried().isEmpty()
                            && !bot.player().containerMenu.getSlot(0)
                                    .getItem().isEmpty(),
                    "Villager trade never reached a real carried payment",
                    cleanup,
                    () -> requestCancellation(helper, bot, villager, offer, tracked,
                            cleanup));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void requestCancellation(
            GameTestHelper helper,
            TestBot bot,
            Villager villager,
            MerchantOffer offer,
            TrackedTradeAction tracked,
            P2GameTestSupport.Cleanup cleanup) {
        try {
            ActionMailbox.Cancellation cancellation = bot.manager().cancelAction(
                    bot.player().getUUID(), tracked.actionId(),
                    ActionCancellationReason.REQUESTED);
            P2GameTestSupport.require(
                    cancellation.status()
                            == ActionMailbox.CancellationStatus.ENQUEUED,
                    "Villager trade cancellation was not enqueued: "
                            + cancellation.status());
            P2GameTestSupport.awaitOutcome(
                    helper, tracked.completion(), 80, cleanup, outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.CANCELLED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.CANCELLED,
                                    "Villager trade did not report cancellation: "
                                            + outcome);
                            requireNativeEmptyMenu(bot,
                                    "Cancelled villager trade leaked menu or cursor");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.WHEAT)
                                                    == SOURCE_COUNT
                                            && inventoryCount(bot,
                                                    Items.EMERALD) == 0
                                            && offer.getUses() == 0
                                            && villager.getVillagerXp() == 0,
                                    "Cancelled villager trade did not return exact inventory/offer state");
                        } finally {
                            cleanup.run();
                        }
                        helper.succeed();
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(exception.getMessage() == null
                    ? exception.toString() : exception.getMessage());
        }
    }

    private static Villager spawnVillager(
            GameTestHelper helper, P2GameTestSupport.Cleanup cleanup) {
        Villager villager = EntityType.VILLAGER.create(helper.getLevel());
        if (villager == null) {
            throw new IllegalStateException("GameTest could not create Villager");
        }
        Vec3 position = helper.absoluteVec(new Vec3(
                RELATIVE_VILLAGER.getX() + 0.5D,
                RELATIVE_VILLAGER.getY(),
                RELATIVE_VILLAGER.getZ() + 0.5D));
        villager.moveTo(position.x, position.y, position.z, 180.0F, 0.0F);
        villager.setNoAi(true);
        villager.setInvulnerable(true);
        helper.getLevel().addFreshEntity(villager);
        cleanup.add(villager::discard);
        return villager;
    }

    private static MerchantOffer offer(Villager villager) {
        MerchantOffer offer = new MerchantOffer(
                new ItemCost(Items.WHEAT, COST_COUNT),
                Optional.empty(),
                new ItemStack(Items.EMERALD),
                4,
                1,
                0.05F);
        MerchantOffers offers = new MerchantOffers();
        offers.add(offer);
        villager.overrideOffers(offers);
        return offer;
    }

    private static void prepareStrictInventory(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = OUTPUT_SLOT;
        for (int slot = 0; slot <= 35; slot++) {
            if (slot == SOURCE_SLOT) {
                bot.player().getInventory().setItem(slot,
                        new ItemStack(Items.WHEAT, SOURCE_COUNT));
            } else if (slot != OUTPUT_SLOT) {
                bot.player().getInventory().setItem(slot,
                        new ItemStack(Items.COBBLESTONE, 64));
            }
        }
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        requireNativeEmptyMenu(bot,
                "Villager trade fixture did not start in native empty-cursor menu");
    }

    private static WorldInteractionActionSpec.WorldVillagerTrade action(
            TestBot bot, Villager villager, MerchantOffer offer) {
        ItemStackFingerprint source = MinecraftActionSnapshot.item(
                bot.player(), bot.player().getInventory().getItem(SOURCE_SLOT));
        ItemStackFingerprint cost = MinecraftActionSnapshot.item(
                bot.player(), offer.getCostA());
        ItemStackFingerprint result = MinecraftActionSnapshot.item(
                bot.player(), offer.getResult());
        WorldInteractionActionSpec.WorldVillagerTrade.MerchantOfferState
                offerState = new WorldInteractionActionSpec
                        .WorldVillagerTrade.MerchantOfferState(
                                MinecraftActionSnapshot.item(
                                        bot.player(), offer.getBaseCostA()),
                                offer.getDemand(),
                                offer.getSpecialPriceDiff(),
                                Float.floatToIntBits(
                                        offer.getPriceMultiplier()),
                                offer.getXp(),
                                offer.shouldRewardExp());
        return new WorldInteractionActionSpec.WorldVillagerTrade(
                WorldInteractionActionSpec.Hand.MAIN_HAND,
                MinecraftActionSnapshot.entity(bot.player(), villager),
                0,
                SOURCE_SLOT,
                OUTPUT_SLOT,
                source,
                cost,
                result,
                offerState,
                villager.getVillagerData().getLevel(),
                villager.getVillagerXp(),
                offer.getUses(),
                offer.getMaxUses(),
                new MenuTransactionLimits(COST_COUNT + 3, 120));
    }

    private static TrackedTradeAction submitTracked(
            TestBot bot,
            WorldInteractionAction action,
            int maximumTicks,
            String phase) {
        long currentTick = bot.player().serverLevel().getServer()
                .getTickCount();
        UUID actionId = UUID.randomUUID();
        ActionMailbox.Submission submission = bot.manager().submitAction(
                new ActionEnvelope(
                        actionId,
                        bot.player().getUUID(),
                        bot.player().runtimeHandle().generation(),
                        "gametest/p5/villager-trade/" + phase + "/" + actionId,
                        currentTick + maximumTicks + 40L,
                        maximumTicks,
                        action,
                        ActionOrigin.none()),
                ActionPriority.OWNER_TASK);
        P2GameTestSupport.require(
                submission.status() == ActionMailbox.SubmissionStatus.ENQUEUED,
                "Tracked villager trade action was rejected: "
                        + submission.status());
        return new TrackedTradeAction(
                actionId, submission.completion().orElseThrow());
    }

    private static boolean hasEvidence(
            ActionOutcome outcome, String key, String value) {
        return outcome.evidence().stream().anyMatch(evidence ->
                evidence.key().equals(key) && evidence.value().equals(value));
    }

    private static void requireNativeEmptyMenu(TestBot bot, String message) {
        P2GameTestSupport.require(
                bot.player().containerMenu == bot.player().inventoryMenu
                        && bot.player().inventoryMenu.getClass()
                                == InventoryMenu.class
                        && bot.player().inventoryMenu.getCarried().isEmpty(),
                message);
    }

    private static int inventoryCount(TestBot bot, Item item) {
        int total = 0;
        for (int slot = 0;
                slot < bot.player().getInventory().getContainerSize();
                slot++) {
            ItemStack stack = bot.player().getInventory().getItem(slot);
            if (stack.is(item)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private record TrackedTradeAction(
            UUID actionId, CompletionStage<ActionOutcome> completion) {
    }
}
