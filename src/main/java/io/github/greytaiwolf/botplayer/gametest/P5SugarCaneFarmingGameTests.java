package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.SugarCaneFarmingSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftSugarCaneFarmingSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntime;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeBudget;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5B 最小甘蔗收获的真实动作验收。
 *
 * <p>每个场景在服务器线程临时装配 {@link SkillRuntime}，但副作用仍经正式 action
 * mailbox、原版破坏、同步 drop receipt 和原版碰撞拾取。测试准备和故障注入可以布置方块或
 * 库存；生产 handler 从不使用这些直接写入路径。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5SugarCaneFarmingGameTests {
    private static final String BATCH = "p5b_sugar_cane";
    private static final int TIMEOUT_TICKS = 480;
    /*
     * Keep the upper-cane drop inside the spawned bot's vanilla collision box.
     * PickupWait deliberately never moves the body; it only proves a normal
     * collision pickup of the frozen receipt UUID.
     */
    private static final BlockPos RELATIVE_BASE = new BlockPos(4, 1, 4);
    private static final BlockPos RELATIVE_TARGET = RELATIVE_BASE.above();

    private P5SugarCaneFarmingGameTests() {
    }

    /**
     * Sugar cane is an {@code instabreak()} block, so the real START packet must
     * emit the one-item provenance receipt before this run can succeed; inventory
     * change alone is insufficient in the handler.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void upperCaneHarvestCollectsExactVanillaDropAndPreservesBase(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareTwoHighCane(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "sugar_cane_harvest_success");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot);
            CaneRuntime runtime = startRuntime(bot, targetPosition(helper));
            cleanup.add(runtime::close);
            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "sugar cane runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            SkillRunView view = runtime.view();
                            P2GameTestSupport.require(
                                    view.state() == SkillRunState.SUCCEEDED,
                                    "Upper sugar cane harvest did not succeed: "
                                            + view);
                            BlockPos target = targetPosition(helper);
                            BlockPos base = target.below();
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    target).isAir()
                                            && bot.player().serverLevel()
                                                            .getBlockState(base)
                                                    .is(Blocks.SUGAR_CANE)
                                            && bot.player().serverLevel()
                                                            .getBlockState(target
                                                                    .above())
                                                    .isAir(),
                                    "Successful cane harvest did not leave only the original base");
                            P2GameTestSupport.require(
                                    count(bot, Items.SUGAR_CANE) == 1
                                            && noCaneDropsRemain(bot, target),
                                    "Cane success did not prove exactly one collected native drop");
                            requireNativeEmptyInventory(bot);
                            P2GameTestSupport.require(
                                    bot.player().getInventory().selected == 0,
                                    "Cane success changed the selected hotbar slot");
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

    /** Cancellation while the break is queued must leave the complete column untouched. */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void cancellingQueuedHarvestLeavesColumnAndInventoryUntouched(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareTwoHighCane(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "sugar_cane_cancel_queued");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot);
            CaneRuntime runtime = startRuntime(bot, targetPosition(helper));
            cleanup.add(runtime::close);
            runtime.tick();
            P2GameTestSupport.require(
                    runtime.runtime().cancel(runtime.runId(), currentTick(bot),
                            "GameTest cancellation")
                            == SkillRuntime.CancelStatus.CANCELLED,
                    "Sugar cane runtime did not accept queued cancellation");
            helper.runAfterDelay(4L, () -> {
                try {
                    P2GameTestSupport.require(
                            runtime.view().state() == SkillRunState.CANCELLED,
                            "Cancelled cane run was not terminally cancelled: "
                                    + runtime.view());
                    requireTwoHighColumn(bot, targetPosition(helper));
                    P2GameTestSupport.require(
                            count(bot, Items.SUGAR_CANE) == 0,
                            "Cancelled queued cane harvest changed inventory");
                    requireNativeEmptyInventory(bot);
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

    /** A changed base is rejected before the queued break can be dispatched. */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void baseMutationAfterPreflightFailsClosedBeforeBreak(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareTwoHighCane(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "sugar_cane_base_mutation");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot);
            CaneRuntime runtime = startRuntime(bot, targetPosition(helper));
            cleanup.add(runtime::close);
            runtime.tick();
            /* Keep the upper target physically valid while changing only its frozen
             * base fingerprint. Otherwise normal cane neighbour physics could erase
             * the target before the action boundary gets a chance to reject it. */
            helper.setBlock(RELATIVE_BASE.east(), Blocks.WATER);
            helper.setBlock(RELATIVE_BASE, Blocks.SAND);
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "base-mutated cane runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            P2GameTestSupport.require(
                                    runtime.view().state()
                                            == SkillRunState.FAILED,
                                    "Mutated cane base was not failed closed: "
                                            + runtime.view());
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    targetPosition(helper))
                                                    .is(Blocks.SUGAR_CANE)
                                            && bot.player().serverLevel()
                                                            .getBlockState(
                                                                    targetPosition(
                                                                            helper)
                                                                            .below())
                                                    .is(Blocks.SAND)
                                            && count(bot, Items.SUGAR_CANE)
                                                    == 0,
                                    "Base-drift rejection broke cane or changed inventory");
                            requireNativeEmptyInventory(bot);
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

    /** The handler refuses to create an uncollectable live drop when storage is full. */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void insufficientStorageFailsBeforeHarvestingTheCane(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareTwoHighCane(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "sugar_cane_insufficient_storage");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            fillStorage(bot);
            CaneRuntime runtime = startRuntime(bot, targetPosition(helper));
            cleanup.add(runtime::close);
            P2GameTestSupport.awaitCondition(
                    helper,
                    20,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "storage-gated cane runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            P2GameTestSupport.require(
                                    runtime.view().state()
                                            == SkillRunState.FAILED,
                                    "Insufficient cane storage was not failed closed: "
                                            + runtime.view());
                            requireTwoHighColumn(bot, targetPosition(helper));
                            P2GameTestSupport.require(
                                    count(bot, Items.SUGAR_CANE) == 0
                                            && noCaneDropsRemain(bot,
                                                    targetPosition(helper)),
                                    "Storage-gated cane harvest created inventory or world drops");
                            requireNativeEmptyInventory(bot);
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

    /**
     * The third segment appears after the skill-layer preflight but before the
     * mailbox dispatch. The BREAK_BLOCK neighbour fence must reject it before the
     * target can be broken, so a cascade never becomes a successful harvest.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void thirdSegmentInsertedBeforeDispatchPreventsAnyBreak(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareTwoHighCane(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "sugar_cane_third_segment_race");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot);
            CaneRuntime runtime = startRuntime(bot, targetPosition(helper));
            cleanup.add(runtime::close);
            runtime.tick();
            helper.setBlock(RELATIVE_TARGET.above(), Blocks.SUGAR_CANE);
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "third-segment cane runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            P2GameTestSupport.require(
                                    runtime.view().state()
                                            == SkillRunState.FAILED,
                                    "Third cane segment was not rejected: "
                                            + runtime.view());
                            BlockPos target = targetPosition(helper);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    target.below())
                                                    .is(Blocks.SUGAR_CANE)
                                            && bot.player().serverLevel()
                                                            .getBlockState(target)
                                                    .is(Blocks.SUGAR_CANE)
                                            && bot.player().serverLevel()
                                                            .getBlockState(target
                                                                    .above())
                                                    .is(Blocks.SUGAR_CANE)
                                            && count(bot, Items.SUGAR_CANE)
                                                    == 0,
                                    "Third-segment fence allowed a cane break or inventory change");
                            requireNativeEmptyInventory(bot);
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

    /**
     * Once the original action has actually broken the top segment, a base drift
     * before the queued completion is processed cannot be converted into a
     * receipt/inventory success or a UUID pickup continuation.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void baseDriftAfterBreakFailsWithoutFalsePickupSuccess(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareTwoHighCane(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "sugar_cane_post_break_base_drift");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot);
            CaneRuntime runtime = startRuntime(bot, targetPosition(helper));
            cleanup.add(runtime::close);
            runtime.tick();
            P2GameTestSupport.awaitCondition(
                    helper,
                    120,
                    () -> bot.player().serverLevel().getBlockState(
                            targetPosition(helper)).isAir(),
                    () -> "cane was never broken before post-break drift: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        helper.setBlock(RELATIVE_BASE, Blocks.DIRT);
                        P2GameTestSupport.awaitCondition(
                                helper,
                                120,
                                () -> {
                                    runtime.tick();
                                    return runtime.view().state()
                                            .isTerminal();
                                },
                                () -> "post-break base-drift runtime did not terminate: "
                                        + runtime.view(),
                                cleanup,
                                () -> {
                                    try {
                                        P2GameTestSupport.require(
                                                runtime.view().state()
                                                        == SkillRunState.FAILED,
                                                "Post-break base drift was not failed closed: "
                                                        + runtime.view());
                                        BlockPos target = targetPosition(helper);
                                        P2GameTestSupport.require(
                                                bot.player().serverLevel()
                                                                .getBlockState(target)
                                                                .isAir()
                                                        && bot.player()
                                                                        .serverLevel()
                                                                        .getBlockState(target
                                                                                .below())
                                                                        .is(Blocks.DIRT),
                                                "Post-break drift unexpectedly restored or rewrote the cane base");
                                        requireNativeEmptyInventory(bot);
                                    } finally {
                                        cleanup.run();
                                    }
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static CaneRuntime startRuntime(TestBot bot, BlockPos target) {
        SkillRegistry registry = new SkillRegistry(4);
        SugarCaneFarmingPlanCompiler.descriptors().forEach(descriptor ->
                P2GameTestSupport.require(registry.register(descriptor)
                                == SkillRegistry.RegisterStatus.REGISTERED,
                        "Could not register sugar cane descriptor"));
        AtomicReference<SkillRuntime> runtimeRef = new AtomicReference<>();
        ActionBackedSkillNodeHandler.ActionGateway actions =
                new ActionBackedSkillNodeHandler.ActionGateway() {
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
                };
        MinecraftSugarCaneFarmingSkillNodeHandler.Resolver resolver =
                (botId, generation) -> bot.player().getUUID().equals(botId)
                        && bot.player().runtimeHandle().generation()
                                == generation
                        ? Optional.of(bot.player())
                        : Optional.empty();
        ActionBackedSkillNodeHandler.SignalSink signals = signal ->
                runtimeRef.get().offerSignal(signal);
        SkillRuntime runtime = new SkillRuntime(
                registry,
                new SkillPlanValidator(registry, SkillPlanLimits.defaults()),
                new ResourceReservationService(32, 240),
                new SkillRuntimeBudget(1, 4, 4, 480));
        runtimeRef.set(runtime);
        P2GameTestSupport.require(
                runtime.registerHandler(
                        SugarCaneFarmingSkillIds.HARVEST_UPPER_SUGAR_CANE,
                        SugarCaneFarmingSkillIds.VERSION,
                        new MinecraftSugarCaneFarmingSkillNodeHandler(
                                resolver, actions, signals))
                        == SkillRuntime.HandlerRegistrationStatus.REGISTERED,
                "Could not register sugar cane farming handler");
        long tick = currentTick(bot);
        SkillRunSubmission submission = runtime.submit(new SkillRunRequest(
                bot.player().getUUID(),
                bot.player().runtimeHandle().generation(),
                SugarCaneFarmingPlanCompiler.compile(bot.player().getUUID(),
                        1L, new BlockCoordinates(target.getX(), target.getY(),
                                target.getZ())),
                tick));
        P2GameTestSupport.require(
                submission.status() == SkillRunSubmission.Status.ACCEPTED,
                "Sugar cane plan was rejected: " + submission);
        return new CaneRuntime(runtime, submission.runId().orElseThrow(), bot);
    }

    private static void prepareTwoHighCane(GameTestHelper helper) {
        helper.setBlock(RELATIVE_BASE.below(), Blocks.SAND);
        helper.setBlock(RELATIVE_BASE.below().east(), Blocks.WATER);
        helper.setBlock(RELATIVE_BASE, Blocks.SUGAR_CANE);
        helper.setBlock(RELATIVE_TARGET, Blocks.SUGAR_CANE);
    }

    private static void prepareEmptyInventory(TestBot bot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void fillStorage(TestBot bot) {
        prepareEmptyInventory(bot);
        for (int inventorySlot = 0; inventorySlot <= 35; inventorySlot++) {
            bot.player().getInventory().setItem(inventorySlot,
                    new ItemStack(Items.DIRT, 1));
        }
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void requireTwoHighColumn(TestBot bot, BlockPos target) {
        P2GameTestSupport.require(
                bot.player().serverLevel().getBlockState(target)
                                .is(Blocks.SUGAR_CANE)
                        && bot.player().serverLevel().getBlockState(
                                target.below()).is(Blocks.SUGAR_CANE)
                        && bot.player().serverLevel().getBlockState(
                                target.above()).isAir(),
                "Cane column was not preserved exactly");
    }

    private static void requireNativeEmptyInventory(TestBot bot) {
        P2GameTestSupport.require(
                bot.player().containerMenu == bot.player().inventoryMenu
                        && bot.player().inventoryMenu.getCarried().isEmpty(),
                "Sugar cane farming left an open menu or non-empty cursor");
    }

    private static BlockPos targetPosition(GameTestHelper helper) {
        return helper.absolutePos(RELATIVE_TARGET);
    }

    private static long currentTick(TestBot bot) {
        return bot.player().serverLevel().getServer().getTickCount();
    }

    private static int count(TestBot bot, Item item) {
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

    private static boolean noCaneDropsRemain(TestBot bot, BlockPos target) {
        return bot.player().serverLevel().getEntitiesOfClass(
                ItemEntity.class,
                new AABB(target.getX() - 2.0D,
                        target.getY() - 2.0D,
                        target.getZ() - 2.0D,
                        target.getX() + 3.0D,
                        target.getY() + 3.0D,
                        target.getZ() + 3.0D),
                item -> item.getItem().is(Items.SUGAR_CANE)).isEmpty();
    }

    private record CaneRuntime(
            SkillRuntime runtime, UUID runId, TestBot bot) {
        private void tick() {
            runtime.tick(currentTick(bot));
        }

        private SkillRunView view() {
            return runtime.inspectRun(runId).orElseThrow();
        }

        private void close() {
            runtime.close();
        }
    }
}
