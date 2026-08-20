package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.technique.bridge.ShieldHoldTechniqueBridge;
import io.github.greytaiwolf.botplayer.technique.combat.ShieldHoldSubmission;
import io.github.greytaiwolf.botplayer.technique.combat.ShieldHoldTechnique;
import io.github.greytaiwolf.botplayer.technique.core.TechniqueFailureCode;
import io.github.greytaiwolf.botplayer.technique.runtime.TechniqueState;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Real-player P5C-S1 acceptance tests for the fixed shield-hold route. */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5ShieldHoldTechniqueGameTests {
    private static final String BATCH = "p5_shield_hold";
    private static final int TIMEOUT_TICKS = 160;

    private P5ShieldHoldTechniqueGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void preEquippedOffhandShieldUsesThenReleasesWithoutInventoryDrift(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper, "fixed-shield-hold");
        TestBot bot = fixture.spawn("guard");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareOffhandShield(bot);
            ItemStackFingerprint before = MinecraftActionSnapshot.item(
                    bot.player(), bot.player().getOffhandItem());
            ShieldHoldSubmission submission = bot.manager().startShieldHold(
                    bot.name());
            P2GameTestSupport.require(submission.accepted(),
                    "Fixed shield hold was rejected: "
                            + submission.status() + " "
                            + submission.safeSummary());
            AtomicLong observedUseTick = new AtomicLong(-1L);
            P2GameTestSupport.awaitCondition(helper, 80,
                    () -> isUsingOffhandShield(bot),
                    "Fixed shield hold never reached vanilla off-hand use",
                    cleanup,
                    () -> {
                        observedUseTick.set(currentTick(bot));
                        awaitSuccessfulRelease(helper, bot, before,
                                observedUseTick, cleanup);
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
    public static void mainHandShieldCannotBecomeTheFixedOffhandRoute(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "offhand-shield-admission");
        TestBot bot = fixture.spawn("guard");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            bot.player().getInventory().clearContent();
            bot.player().setItemSlot(EquipmentSlot.MAINHAND,
                    new ItemStack(Items.SHIELD));
            bot.player().setItemSlot(EquipmentSlot.OFFHAND, ItemStack.EMPTY);
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();

            ShieldHoldSubmission submission = bot.manager().startShieldHold(
                    bot.name());
            P2GameTestSupport.require(submission.status()
                            == ShieldHoldSubmission.Status.NO_OFFHAND_SHIELD,
                    "Main-hand shield unexpectedly entered the off-hand route: "
                            + submission);
            P2GameTestSupport.require(!bot.player().isUsingItem(),
                    "Rejected off-hand shield route started vanilla item use");
            requireNativeEmptyMenu(bot);
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static void awaitSuccessfulRelease(GameTestHelper helper,
            TestBot bot, ItemStackFingerprint before, AtomicLong observedUseTick,
            P2GameTestSupport.Cleanup cleanup) {
        P2GameTestSupport.awaitCondition(helper, 80,
                () -> bot.manager().latestTechniqueOutcome(
                        bot.player().getUUID()).filter(outcome ->
                                outcome.techniqueId().equals(
                                        ShieldHoldTechnique.ID)
                                        && outcome.techniqueVersion().equals(
                                                ShieldHoldTechnique.VERSION)
                                        && outcome.state()
                                                == TechniqueState.SUCCEEDED
                                        && outcome.failureCode()
                                                == TechniqueFailureCode.NONE)
                        .isPresent(),
                "Fixed shield hold did not reach a successful Technique terminal",
                cleanup,
                () -> {
                    long finishedTick = currentTick(bot);
                    P2GameTestSupport.require(finishedTick
                                    - observedUseTick.get()
                            >= ShieldHoldTechniqueBridge.HOLD_TICKS,
                            "Shield was released before its fixed hold window");
                    P2GameTestSupport.require(!bot.player().isUsingItem(),
                            "Technique terminal left the bot using its shield");
                    P2GameTestSupport.require(MinecraftActionSnapshot.item(
                                    bot.player(), bot.player().getOffhandItem())
                                    .equals(before),
                            "Fixed shield hold changed the off-hand stack");
                    requireNativeEmptyMenu(bot);
                    cleanup.run();
                    helper.succeed();
                });
    }

    private static void prepareOffhandShield(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().setItemSlot(EquipmentSlot.OFFHAND,
                new ItemStack(Items.SHIELD));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        requireNativeEmptyMenu(bot);
    }

    private static boolean isUsingOffhandShield(TestBot bot) {
        return bot.player().isUsingItem()
                && bot.player().getUsedItemHand() == InteractionHand.OFF_HAND
                && bot.player().getUseItem().is(Items.SHIELD);
    }

    private static void requireNativeEmptyMenu(TestBot bot) {
        P2GameTestSupport.require(bot.player().containerMenu
                        == bot.player().inventoryMenu
                        && bot.player().inventoryMenu.getClass()
                                == InventoryMenu.class
                        && bot.player().inventoryMenu.stillValid(bot.player())
                        && bot.player().inventoryMenu.slots.size()
                                == MenuFamily.INVENTORY_2X2.slotCount()
                        && bot.player().inventoryMenu.getCarried().isEmpty(),
                "Shield fixture did not retain a native inventory menu with an empty cursor");
    }

    private static long currentTick(TestBot bot) {
        return bot.player().serverLevel().getServer().getTickCount();
    }
}
