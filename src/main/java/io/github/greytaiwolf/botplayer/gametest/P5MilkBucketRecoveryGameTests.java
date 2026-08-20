package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.builtin.recovery.VanillaMilkBucketRecovery;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftMilkBucketRecoverySkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntime;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeBudget;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * P5B acceptance tests for the isolated milk-bucket recovery handler.
 *
 * <p>Fixtures may seed poison, a milk bucket or deliberate drift. No test
 * writes the desired postcondition: both bucket conversion and effect clearing
 * must come from the action backend's normal {@code USE_ITEM} packet path.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5MilkBucketRecoveryGameTests {
    private static final String BATCH = "p5_milk_bucket_recovery";
    private static final int TIMEOUT_TICKS = 260;

    private P5MilkBucketRecoveryGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void realMilkUseClearsPoisonAndProducesOneBucket(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_success");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            driveUntilTerminal(helper, bot, run, cleanup, 180, terminal -> {
                P2GameTestSupport.require(
                        terminal.state() == SkillRunState.SUCCEEDED,
                        "Milk recovery did not succeed: "
                                + terminal.safeSummary());
                P2GameTestSupport.require(
                        !bot.player().hasEffect(MobEffects.POISON)
                                && bot.player().getActiveEffects().isEmpty(),
                        "Successful milk recovery left an active effect");
                P2GameTestSupport.require(
                        bot.player().getInventory().selected == 0
                                && bot.player().getInventory().getItem(0)
                                        .is(Items.BUCKET)
                                && bot.player().getInventory().getItem(0)
                                        .getCount() == 1,
                        "Real milk use did not produce exactly one selected empty bucket");
                requireNativeEmptyCursor(bot);
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
    public static void queuedCancellationLeavesMilkAndPoisonUntouched(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_cancel");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            run.runtime().tick(currentTick(bot));
            P2GameTestSupport.require(
                    run.runtime().cancel(run.runId(), currentTick(bot),
                            "P5 milk recovery cancellation GameTest")
                                    == SkillRuntime.CancelStatus.CANCELLED,
                    "Queued milk recovery did not accept cancellation");
            helper.runAfterDelay(5L, () -> {
                try {
                    SkillRunView terminal = run.view();
                    P2GameTestSupport.require(
                            terminal.state() == SkillRunState.CANCELLED,
                            "Cancelled milk recovery reached an unexpected state");
                    P2GameTestSupport.require(
                            bot.player().hasEffect(MobEffects.POISON)
                                    && bot.player().getInventory().getItem(0)
                                            .is(Items.MILK_BUCKET)
                                    && bot.player().getInventory().getItem(0)
                                            .getCount() == 1,
                            "Cancellation dispatched milk use or changed the recovery inventory");
                    requireNativeEmptyCursor(bot);
                    cleanup.run();
                    helper.succeed();
                } catch (RuntimeException | AssertionError exception) {
                    cleanup.run();
                    helper.fail(message(exception));
                }
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * The handler has already frozen its strict action snapshot when the fixture
     * adds a second effect. The backend must reject before it sends the vanilla
     * packet, rather than letting milk erase a state it never approved.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void effectDriftBeforeDispatchFailsWithoutMilkUse(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_effect_drift");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            run.runtime().tick(currentTick(bot));
            /* Deliberate test-fixture drift after handler capture, before mailbox tick. */
            bot.player().addEffect(new MobEffectInstance(
                    MobEffects.MOVEMENT_SPEED, 240, 0));
            driveUntilTerminal(helper, bot, run, cleanup, 80, terminal -> {
                P2GameTestSupport.require(
                        terminal.state() == SkillRunState.FAILED
                                && terminal.failureCode().orElseThrow()
                                        == SkillFailureCode.WORLD_CHANGED,
                        "Effect drift was not failed closed before native use: "
                                + terminal.safeSummary());
                P2GameTestSupport.require(
                        bot.player().hasEffect(MobEffects.POISON)
                                && bot.player().hasEffect(
                                        MobEffects.MOVEMENT_SPEED)
                                && bot.player().getInventory().getItem(0)
                                        .is(Items.MILK_BUCKET),
                        "Effect-drift rejection still consumed milk or cleared effects");
                requireNativeEmptyCursor(bot);
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * The same strict effect fence must hold for the full natural-use window,
     * not only before the packet is sent. Otherwise a newly-added effect could
     * be erased by milk and the empty final effect set would falsely look safe.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void effectDriftDuringMilkUseStopsWithoutConsumption(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_effect_drift_in_flight");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            waitForActiveMilkUse(helper, bot, run, cleanup, 80, () -> {
                /* Fixture-only concurrent effect; production never adds it. */
                bot.player().addEffect(new MobEffectInstance(
                        MobEffects.MOVEMENT_SPEED, 240, 0));
                driveUntilTerminal(helper, bot, run, cleanup, 80, terminal -> {
                    P2GameTestSupport.require(
                            terminal.state() == SkillRunState.FAILED
                                    && terminal.failureCode().orElseThrow()
                                            == SkillFailureCode.WORLD_CHANGED,
                            "In-flight effect drift was not failed closed: "
                                    + terminal.safeSummary());
                    P2GameTestSupport.require(
                            bot.player().hasEffect(MobEffects.POISON)
                                    && bot.player().hasEffect(
                                            MobEffects.MOVEMENT_SPEED)
                                    && bot.player().getInventory().getItem(0)
                                            .is(Items.MILK_BUCKET)
                                    && bot.player().getInventory().getItem(0)
                                            .getCount() == 1,
                            "In-flight effect drift consumed milk or erased an unapproved effect");
                    requireNativeEmptyCursor(bot);
                });
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * A normal PlayerTickEvent.Pre listener can add drift on the very final
     * natural-use tick. The native-use mixin must still stop milk before
     * vanilla removes effects; observing only from lifecycle Post would see an
     * empty result and incorrectly accept the consumption.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void finalTickEffectDriftStopsBeforeMilkConsumes(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_final_tick_effect_drift");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            AtomicBoolean injected = new AtomicBoolean();
            Consumer<PlayerTickEvent.Pre> listener = event -> {
                if (event.getEntity() == bot.player()
                        && bot.player().isUsingItem()
                        && bot.player().getUseItemRemainingTicks() == 1
                        && injected.compareAndSet(false, true)) {
                    bot.player().addEffect(new MobEffectInstance(
                            MobEffects.MOVEMENT_SPEED, 240, 0));
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            cleanup.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            driveUntilTerminal(helper, bot, run, cleanup, 180, terminal -> {
                P2GameTestSupport.require(
                        injected.get(),
                        "Final-tick drift fixture never reached native milk consumption");
                P2GameTestSupport.require(
                        terminal.state() == SkillRunState.FAILED
                                && terminal.failureCode().orElseThrow()
                                        == SkillFailureCode.WORLD_CHANGED,
                        "Final-tick effect drift was not failed closed: "
                                + terminal.safeSummary());
                P2GameTestSupport.require(
                        !bot.player().isUsingItem()
                                && bot.player().hasEffect(MobEffects.POISON)
                                && bot.player().hasEffect(
                                        MobEffects.MOVEMENT_SPEED)
                                && bot.player().getInventory().getItem(0)
                                        .is(Items.MILK_BUCKET)
                                && bot.player().getInventory().getItem(0)
                                        .getCount() == 1,
                        "Final-tick fence consumed milk or cleared an unapproved effect");
                requireNativeEmptyCursor(bot);
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /** The complete native menu/cursor snapshot is also a dispatch fence. */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void cursorDriftBeforeDispatchFailsWithoutMilkUse(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_cursor_drift");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            run.runtime().tick(currentTick(bot));
            /* Fixture-only menu drift: the handler itself never writes a cursor. */
            bot.player().inventoryMenu.setCarried(new ItemStack(Items.DIRT));
            bot.player().inventoryMenu.broadcastChanges();
            driveUntilTerminal(helper, bot, run, cleanup, 80, terminal -> {
                try {
                    P2GameTestSupport.require(
                            terminal.state() == SkillRunState.FAILED
                                    && terminal.failureCode().orElseThrow()
                                            == SkillFailureCode.UNSAFE_CONTROL_STATE,
                            "Cursor drift was not failed closed before native use: "
                                    + terminal.safeSummary());
                    P2GameTestSupport.require(
                            bot.player().hasEffect(MobEffects.POISON)
                                    && bot.player().getInventory().getItem(0)
                                            .is(Items.MILK_BUCKET),
                            "Cursor-drift rejection consumed milk or cleared poison");
                } finally {
                    /* Restore only the test fixture cursor before isolated cleanup. */
                    bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
                    bot.player().inventoryMenu.broadcastChanges();
                }
                requireNativeEmptyCursor(bot);
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /** Strict native-menu/cursor fencing must also remain active while drinking. */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void cursorDriftDuringMilkUseStopsWithoutConsumption(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_cursor_drift_in_flight");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            waitForActiveMilkUse(helper, bot, run, cleanup, 80, () -> {
                bot.player().inventoryMenu.setCarried(new ItemStack(Items.DIRT));
                bot.player().inventoryMenu.broadcastChanges();
                driveUntilTerminal(helper, bot, run, cleanup, 80, terminal -> {
                    try {
                        P2GameTestSupport.require(
                                terminal.state() == SkillRunState.FAILED
                                        && terminal.failureCode().orElseThrow()
                                                == SkillFailureCode.WORLD_CHANGED,
                                "In-flight cursor drift was not failed closed: "
                                        + terminal.safeSummary());
                        P2GameTestSupport.require(
                                bot.player().hasEffect(MobEffects.POISON)
                                        && bot.player().getInventory().getItem(0)
                                                .is(Items.MILK_BUCKET)
                                        && bot.player().getInventory().getItem(0)
                                                .getCount() == 1,
                                "In-flight cursor drift consumed milk or cleared poison");
                    } finally {
                        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
                        bot.player().inventoryMenu.broadcastChanges();
                    }
                    requireNativeEmptyCursor(bot);
                });
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /** A cancellation during an actual use releases native use without success. */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void inFlightCancellationStopsMilkUseWithoutConsumption(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_cancel_in_flight");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            waitForActiveMilkUse(helper, bot, run, cleanup, 80, () -> {
                P2GameTestSupport.require(
                        run.runtime().cancel(run.runId(), currentTick(bot),
                                "P5 in-flight milk recovery cancellation")
                                        == SkillRuntime.CancelStatus.CANCELLED,
                        "In-flight milk recovery did not accept cancellation");
                /*
                 * SkillRuntime terminalizes synchronously, whereas the
                 * ActionBacked cancellation reaches native RELEASE/stop via
                 * the manager mailbox in a later server Post phase.
                 */
                helper.runAfterDelay(2L, () -> driveUntilTerminal(
                        helper, bot, run, cleanup, 80, terminal -> {
                            P2GameTestSupport.require(
                                    terminal.state() == SkillRunState.CANCELLED
                                            && !bot.player().isUsingItem(),
                                    "In-flight cancellation left milk use or reached an unexpected state");
                            P2GameTestSupport.require(
                                    bot.player().hasEffect(MobEffects.POISON)
                                            && bot.player().getInventory().getItem(0)
                                                    .is(Items.MILK_BUCKET)
                                            && bot.player().getInventory().getItem(0)
                                                    .getCount() == 1,
                                    "In-flight cancellation consumed milk or cleared poison");
                            requireNativeEmptyCursor(bot);
                        }));
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * A cancellation issued by a normal PlayerTickEvent.Pre listener on the
     * last natural-use tick must become visible to the native-use mixin before
     * vanilla consumes milk. The ordinary action mailbox drains later in the
     * lifecycle tick, so this specifically proves the exact pre-consumption
     * cancellation fence rather than the older in-flight cancellation path.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void finalTickCancellationStopsBeforeMilkConsumes(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_final_tick_cancel");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            AtomicBoolean cancelled = new AtomicBoolean();
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            Consumer<PlayerTickEvent.Pre> listener = event -> {
                if (event.getEntity() == bot.player()
                        && bot.player().isUsingItem()
                        && bot.player().getUseItemRemainingTicks() == 1
                        && cancelled.compareAndSet(false, true)) {
                    P2GameTestSupport.require(
                            run.runtime().cancel(run.runId(), currentTick(bot),
                                    "P5 final-tick milk cancellation")
                                    == SkillRuntime.CancelStatus.CANCELLED,
                            "Final-tick milk cancellation was not accepted");
                    ActionMailbox.Cancellation cancellation = run
                            .strictNaturalUseCancellation().get();
                    P2GameTestSupport.require(cancellation != null
                                    && cancellation.status()
                                            == ActionMailbox
                                                    .CancellationStatus
                                                    .ENQUEUED,
                            "Final-tick milk cancellation was not accepted by the strict action lane");
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            cleanup.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            driveUntilTerminal(helper, bot, run, cleanup, 180, terminal -> {
                P2GameTestSupport.require(cancelled.get(),
                        "Final-tick cancellation fixture never reached native milk use");
                P2GameTestSupport.require(
                        terminal.state() == SkillRunState.CANCELLED
                                && !bot.player().isUsingItem(),
                        "Final-tick cancellation did not stop native milk use: "
                                + terminal.safeSummary());
                P2GameTestSupport.require(
                        bot.player().hasEffect(MobEffects.POISON)
                                && bot.player().getInventory().getItem(0)
                                        .is(Items.MILK_BUCKET)
                                && bot.player().getInventory().getItem(0)
                                        .getCount() == 1,
                        "Final-tick cancellation consumed milk or cleared poison");
                requireNativeEmptyCursor(bot);
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * A full cancellation lane must fail closed before the native final tick,
     * rather than letting SkillRuntime's local terminal state claim safety.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void finalTickCancellationRejectsClosedLaneBeforeMilkConsumes(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "milk_recovery_final_tick_cancel_closed_lane");
        TestBot bot = fixture.spawn("medic");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            preparePoisonedMilk(bot);
            AtomicBoolean cancelled = new AtomicBoolean();
            AtomicReference<ActionMailbox.CancellationStatus> laneRejection =
                    new AtomicReference<>();
            RecoveryRun run = startRun(bot);
            cleanup.add(run.runtime()::close);
            Consumer<PlayerTickEvent.Pre> listener = event -> {
                if (event.getEntity() != bot.player()
                        || !bot.player().isUsingItem()
                        || bot.player().getUseItemRemainingTicks() != 1
                        || !cancelled.compareAndSet(false, true)) {
                    return;
                }
                int cancellationCapacity = Math.max(1,
                        BotPlayerConfig.ACTION_MAILBOX_CAPACITY.get() / 8);
                for (int index = 0; index <= cancellationCapacity; index++) {
                    ActionMailbox.Cancellation filler = bot.manager()
                            .cancelAction(bot.player().getUUID(),
                                    new UUID(0L, 20_000L + index),
                                    ActionCancellationReason.REQUESTED);
                    if (filler.status()
                            != ActionMailbox.CancellationStatus.ENQUEUED) {
                        laneRejection.set(filler.status());
                        break;
                    }
                }
                ActionMailbox.CancellationStatus rejection = laneRejection.get();
                P2GameTestSupport.require(rejection
                                == ActionMailbox.CancellationStatus.MAILBOX_FULL
                                || rejection
                                        == ActionMailbox.CancellationStatus
                                                .COMPLETION_BACKPRESSURE,
                        "Final-tick fixture could not close the cancellation lane: "
                                + rejection);
                P2GameTestSupport.require(
                        run.runtime().cancel(run.runId(), currentTick(bot),
                                "P5 final-tick cancellation lane-full")
                                        == SkillRuntime.CancelStatus.CANCELLED,
                        "Lane-full milk cancellation was not accepted by SkillRuntime");
                ActionMailbox.Cancellation target = run
                        .strictNaturalUseCancellation().get();
                P2GameTestSupport.require(target != null
                                && (target.status()
                                        == ActionMailbox.CancellationStatus
                                                .MAILBOX_FULL
                                        || target.status()
                                                == ActionMailbox
                                                        .CancellationStatus
                                                        .COMPLETION_BACKPRESSURE),
                        "Strict final-tick cancellation was not rejected by the closed lane");
                ActionMailbox.Submission probe = bot.manager().submitAction(
                        new ActionEnvelope(new UUID(0L, 30_001L),
                                bot.player().getUUID(),
                                bot.player().runtimeHandle().generation(),
                                "p5-final-tick-cancellation-lane-probe",
                                currentTick(bot) + 20L,
                                1,
                                new StopAction(),
                                ActionOrigin.none()),
                        ActionPriority.OWNER_CONTROL);
                P2GameTestSupport.require(probe.status()
                                == ActionMailbox.SubmissionStatus
                                        .BOT_GENERATION_CLOSED,
                        "Rejected strict cancellation did not quarantine its bot generation");
            };
            NeoForge.EVENT_BUS.addListener(listener);
            cleanup.add(() -> NeoForge.EVENT_BUS.unregister(listener));
            driveUntilTerminal(helper, bot, run, cleanup, 180, terminal -> {
                P2GameTestSupport.require(cancelled.get(),
                        "Lane-full final-tick fixture never reached native milk use");
                P2GameTestSupport.require(laneRejection.get() != null,
                        "Lane-full final-tick fixture did not observe a lane rejection");
                P2GameTestSupport.require(
                        terminal.state() == SkillRunState.CANCELLED
                                && !bot.player().isUsingItem(),
                        "Rejected final-tick cancellation left native milk use active: "
                                + terminal.safeSummary());
                P2GameTestSupport.require(
                        bot.player().hasEffect(MobEffects.POISON)
                                && bot.player().getInventory().getItem(0)
                                        .is(Items.MILK_BUCKET)
                                && bot.player().getInventory().getItem(0)
                                        .getCount() == 1,
                        "Rejected final-tick cancellation consumed milk or cleared poison");
                requireNativeEmptyCursor(bot);
            });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static RecoveryRun startRun(TestBot bot) {
        SkillRegistry registry = new SkillRegistry();
        P2GameTestSupport.require(
                registry.register(VanillaMilkBucketRecovery.descriptor())
                        == SkillRegistry.RegisterStatus.REGISTERED,
                "Standalone milk recovery descriptor could not register");
        SkillRuntime runtime = new SkillRuntime(
                registry,
                new SkillPlanValidator(registry, SkillPlanLimits.defaults()),
                new ResourceReservationService(8, 240),
                new SkillRuntimeBudget(1, 4, 8, 240));
        AtomicReference<ActionMailbox.Cancellation> strictNaturalUseCancellation =
                new AtomicReference<>();
        MinecraftMilkBucketRecoverySkillNodeHandler handler =
                new MinecraftMilkBucketRecoverySkillNodeHandler(
                        bot.manager()::resolveActive,
                        actionGateway(bot, strictNaturalUseCancellation),
                        runtime::offerSignal);
        P2GameTestSupport.require(
                runtime.registerHandler(VanillaMilkBucketRecovery.ID,
                        VanillaMilkBucketRecovery.VERSION, handler)
                                == SkillRuntime.HandlerRegistrationStatus
                                        .REGISTERED,
                "Standalone milk recovery handler could not register");
        SkillRunSubmission submission = runtime.submit(new SkillRunRequest(
                bot.player().getUUID(),
                bot.player().runtimeHandle().generation(),
                VanillaMilkBucketRecovery.compile(bot.player().getUUID(),
                        1L,
                        new VanillaMilkBucketRecovery.MilkRequest(0)),
                currentTick(bot)));
        P2GameTestSupport.require(
                submission.status() == SkillRunSubmission.Status.ACCEPTED,
                "Standalone milk recovery plan was not accepted: "
                        + submission.safeSummary());
        return new RecoveryRun(runtime, submission.runId().orElseThrow(),
                strictNaturalUseCancellation);
    }

    private static ActionBackedSkillNodeHandler.ActionGateway actionGateway(
            TestBot bot,
            AtomicReference<ActionMailbox.Cancellation>
                    strictNaturalUseCancellation) {
        return new ActionBackedSkillNodeHandler.ActionGateway() {
            @Override
            public ActionMailbox.Submission submit(
                    ActionEnvelope envelope, ActionPriority priority) {
                return bot.manager().submitAction(envelope, priority);
            }

            @Override
            public void cancel(
                    UUID botId,
                    UUID actionId,
                    ActionCancellationReason reason) {
                bot.manager().cancelAction(botId, actionId, reason);
            }

            @Override
            public void cancelStrictNaturalUse(
                    ActionEnvelope envelope,
                    ActionCancellationReason reason) {
                strictNaturalUseCancellation.set(bot.manager()
                        .cancelStrictNaturalUse(envelope, reason));
            }
        };
    }

    private static void preparePoisonedMilk(TestBot bot) {
        bot.player().removeAllEffects();
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().getInventory().setItem(0,
                new ItemStack(Items.MILK_BUCKET));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
        bot.player().addEffect(new MobEffectInstance(MobEffects.POISON,
                240, 0));
    }

    private static void requireNativeEmptyCursor(TestBot bot) {
        InventoryMenu menu = bot.player().inventoryMenu;
        P2GameTestSupport.require(
                bot.player().containerMenu == menu
                        && menu.getClass() == InventoryMenu.class
                        && menu.stillValid(bot.player())
                        && menu.slots.size()
                                == MenuFamily.INVENTORY_2X2.slotCount()
                        && menu.getCarried().isEmpty(),
                "Milk recovery left a non-exact, invalid, open or cursor-bearing inventory menu");
    }

    private static void driveUntilTerminal(
            GameTestHelper helper,
            TestBot bot,
            RecoveryRun run,
            P2GameTestSupport.Cleanup cleanup,
            int remainingTicks,
            TerminalVerifier verifier) {
        try {
            run.runtime().tick(currentTick(bot));
            SkillRunView view = run.view();
            if (view.state().isTerminal()) {
                verifier.verify(view);
                cleanup.run();
                helper.succeed();
                return;
            }
            if (remainingTicks <= 0) {
                cleanup.run();
                helper.fail("Milk recovery runtime did not reach a terminal state");
                return;
            }
            helper.runAfterDelay(1L, () -> driveUntilTerminal(helper, bot,
                    run, cleanup, remainingTicks - 1, verifier));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(message(exception));
        }
    }

    private static void waitForActiveMilkUse(
            GameTestHelper helper,
            TestBot bot,
            RecoveryRun run,
            P2GameTestSupport.Cleanup cleanup,
            int remainingTicks,
            Runnable whenActive) {
        try {
            run.runtime().tick(currentTick(bot));
            if (run.view().state().isTerminal()) {
                cleanup.run();
                helper.fail("Milk recovery ended before native milk use began");
                return;
            }
            if (bot.player().isUsingItem()) {
                whenActive.run();
                return;
            }
            if (remainingTicks <= 0) {
                cleanup.run();
                helper.fail("Milk recovery never entered native item-use state");
                return;
            }
            helper.runAfterDelay(1L, () -> waitForActiveMilkUse(helper, bot,
                    run, cleanup, remainingTicks - 1, whenActive));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(message(exception));
        }
    }

    private static long currentTick(TestBot bot) {
        return bot.player().serverLevel().getServer().getTickCount();
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null
                ? exception.toString()
                : exception.getMessage();
    }

    private record RecoveryRun(
            SkillRuntime runtime,
            UUID runId,
            AtomicReference<ActionMailbox.Cancellation>
                    strictNaturalUseCancellation) {
        private SkillRunView view() {
            return runtime.inspectRun(runId).orElseThrow(() ->
                    new IllegalStateException(
                            "Milk recovery runtime lost its run view"));
        }
    }

    @FunctionalInterface
    private interface TerminalVerifier {
        void verify(SkillRunView view);
    }
}
