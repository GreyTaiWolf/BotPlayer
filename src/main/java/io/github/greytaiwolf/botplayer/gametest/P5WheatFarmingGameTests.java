package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.interaction.BlockCoordinates;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.farming.WheatFarmingSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftWheatFarmingSkillNodeHandler;
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
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.FarmBlock;
import net.minecraft.world.phys.AABB;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5B 单格小麦农业的真实动作验收。
 *
 * <p>生产生命周期注册仍属于后续窄 Contract 接线；本测试在服务器线程创建一个临时、与现有
 * action mailbox 相连的 {@link SkillRuntime}，因此它覆盖的不是手写 packet，而是完整的
 * SkillRuntime → handler → 原版动作 backend → completion signal 路径。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5WheatFarmingGameTests {
    private static final String BATCH = "p5b_wheat_farming";
    private static final int TIMEOUT_TICKS = 480;
    /*
     * The crop occupies the standard farmer's collision footprint.  Wheat is
     * non-solid, so this still uses normal entity collision pickup while making
     * the all-receipt harvest assertion independent of an unimplemented move
     * phase.
     */
    private static final BlockPos RELATIVE_CROP = new BlockPos(4, 1, 4);

    private P5WheatFarmingGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void matureWheatHarvestsCollectsDropsAndReplants(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareMatureWheat(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "wheat_harvest_replant_success");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareSeeds(bot, 2);
            FarmingRuntime runtime = startRuntime(bot, cropPosition(helper));
            cleanup.add(runtime::close);
            P2GameTestSupport.awaitCondition(
                    helper,
                    TIMEOUT_TICKS - 20,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "wheat farming runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            SkillRunView view = runtime.view();
                            P2GameTestSupport.require(
                                    view.state() == SkillRunState.SUCCEEDED,
                                    "Mature wheat farming did not succeed: "
                                            + view);
                            BlockPos crop = cropPosition(helper);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    crop).is(Blocks.WHEAT)
                                            && bot.player().serverLevel()
                                                            .getBlockState(crop)
                                                            .getValue(CropBlock.AGE)
                                                    == 0,
                                    "Farming success did not leave exact age=0 wheat");
                            P2GameTestSupport.require(
                                    count(bot, Items.WHEAT) == 1
                                            && count(bot, Items.WHEAT_SEEDS)
                                                    >= 1
                                            && count(bot, Items.WHEAT_SEEDS)
                                                    <= 4
                                            && noHarvestDropsRemain(bot, crop),
                                    "Farming did not collect the exact vanilla wheat drops before one seed was replanted");
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                            .getCarried().isEmpty()
                                            && bot.player().getInventory()
                                                            .selected == 0,
                                    "Farming success leaked a menu, cursor, or changed selected hotbar slot");
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
     * 目标在 handler 的第二次 prepare 与 backend dispatch 之间变化时，原版动作必须拒绝，
     * 并且 run 不能把别的作物、种子或 cursor 解释成完成。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void targetMutationAfterPreflightFailsClosedWithoutSeedDebit(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareMatureWheat(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "wheat_target_mutation");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareSeeds(bot, 2);
            FarmingRuntime runtime = startRuntime(bot, cropPosition(helper));
            cleanup.add(runtime::close);
            runtime.tick();
            helper.setBlock(RELATIVE_CROP, Blocks.CARROTS.defaultBlockState()
                    .setValue(CropBlock.AGE, 0));
            P2GameTestSupport.awaitCondition(
                    helper,
                    80,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "mutated wheat runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            P2GameTestSupport.require(
                                    runtime.view().state()
                                            == SkillRunState.FAILED,
                                    "Mutated crop was not failed closed: "
                                            + runtime.view());
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    cropPosition(helper))
                                                    .is(Blocks.CARROTS)
                                            && count(bot, Items.WHEAT_SEEDS)
                                                    == 2,
                                    "Failed crop action changed the replacement crop or seed inventory");
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
     * The crop is never broken when the bounded manifest could not fit in the
     * native storage area. This prevents a real harvest from creating drops that the
     * pickup proof would be unable to absorb.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void insufficientStorageFailsBeforeHarvestingTheCrop(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareMatureWheat(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "wheat_insufficient_storage");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareSeeds(bot, 2);
            fillStorageLeavingOnlyThreeSlots(bot);
            FarmingRuntime runtime = startRuntime(bot, cropPosition(helper));
            cleanup.add(runtime::close);
            P2GameTestSupport.awaitCondition(
                    helper,
                    20,
                    () -> {
                        runtime.tick();
                        return runtime.view().state().isTerminal();
                    },
                    () -> "storage-gated wheat runtime did not terminate: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        try {
                            P2GameTestSupport.require(
                                    runtime.view().state()
                                            == SkillRunState.FAILED,
                                    "Insufficient storage was not failed closed: "
                                            + runtime.view());
                            BlockPos crop = cropPosition(helper);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    crop).is(Blocks.WHEAT)
                                            && bot.player().serverLevel()
                                                            .getBlockState(crop)
                                                            .getValue(CropBlock.AGE)
                                                    == CropBlock.MAX_AGE
                                            && count(bot, Items.WHEAT_SEEDS)
                                                    == 2
                                            && count(bot, Items.WHEAT) == 0,
                                    "Storage-gated harvest mutated crop or inventory");
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
     * Once the original break has made the crop air, any later farmland mutation
     * must stop both the UUID-bound pickup continuation and replant.  The test
     * deliberately does not erase native drops: if vanilla physics collects one
     * during the failure window, that remains an observed world effect rather than
     * a test-injected inventory write.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void postHarvestTargetMutationFailsClosedWithoutReplant(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareMatureWheat(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "wheat_post_harvest_target_mutation");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareSeeds(bot, 2);
            FarmingRuntime runtime = startRuntime(bot, cropPosition(helper));
            cleanup.add(runtime::close);
            P2GameTestSupport.awaitCondition(
                    helper,
                    120,
                    () -> {
                        runtime.tick();
                        return bot.player().serverLevel().getBlockState(
                                cropPosition(helper)).isAir();
                    },
                    () -> "wheat was never broken before post-harvest mutation: "
                            + runtime.view(),
                    cleanup,
                    () -> {
                        helper.setBlock(RELATIVE_CROP.below(), Blocks.DIRT);
                        P2GameTestSupport.awaitCondition(
                                helper,
                                120,
                                () -> {
                                    runtime.tick();
                                    return runtime.view().state()
                                            .isTerminal();
                                },
                                () -> "post-harvest mutated wheat runtime did not terminate: "
                                        + runtime.view(),
                                cleanup,
                                () -> {
                                    try {
                                        P2GameTestSupport.require(
                                                runtime.view().state()
                                                        == SkillRunState.FAILED,
                                                "Post-harvest target mutation was not failed closed: "
                                                        + runtime.view());
                                        BlockPos crop = cropPosition(helper);
                                        P2GameTestSupport.require(
                                                bot.player().serverLevel()
                                                                .getBlockState(crop)
                                                                .isAir()
                                                        && bot.player()
                                                                        .serverLevel()
                                                                        .getBlockState(crop.below())
                                                                        .is(Blocks.DIRT),
                                                "Post-harvest target mutation allowed a replant or restored farmland");
                                        P2GameTestSupport.require(
                                                bot.player().getInventory()
                                                                .selected == 0,
                                                "Post-harvest failure changed the selected hotbar slot");
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

    /**
     * 取消在 harvest action 尚在 mailbox 时发起。ActionBacked handler 必须撤销该动作，run
     * 变为 cancelled，且不留下半开菜单、cursor、种子扣减或目标方块副作用。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void cancellingQueuedHarvestLeavesCropAndInventoryUntouched(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        prepareMatureWheat(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "wheat_cancel_queued_harvest");
        TestBot bot = fixture.spawn("farmr");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareSeeds(bot, 2);
            FarmingRuntime runtime = startRuntime(bot, cropPosition(helper));
            cleanup.add(runtime::close);
            runtime.tick();
            P2GameTestSupport.require(
                    runtime.runtime().cancel(runtime.runId(),
                            currentTick(bot), "GameTest cancellation")
                            == SkillRuntime.CancelStatus.CANCELLED,
                    "Wheat farming runtime did not accept queued cancellation");
            helper.runAfterDelay(4L, () -> {
                try {
                    P2GameTestSupport.require(
                            runtime.view().state()
                                    == SkillRunState.CANCELLED,
                            "Cancelled wheat run was not terminally cancelled: "
                                    + runtime.view());
                    BlockPos crop = cropPosition(helper);
                    P2GameTestSupport.require(
                            bot.player().serverLevel().getBlockState(crop)
                                            .is(Blocks.WHEAT)
                                    && bot.player().serverLevel()
                                                    .getBlockState(crop)
                                                    .getValue(CropBlock.AGE)
                                            == CropBlock.MAX_AGE
                                    && count(bot, Items.WHEAT_SEEDS) == 2,
                            "Cancelled queued harvest changed crop or seeds");
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

    private static FarmingRuntime startRuntime(
            TestBot bot, BlockPos crop) {
        SkillRegistry registry = new SkillRegistry(8);
        WheatFarmingPlanCompiler.descriptors().forEach(descriptor ->
                P2GameTestSupport.require(registry.register(descriptor)
                                == SkillRegistry.RegisterStatus.REGISTERED,
                        "Could not register wheat farming descriptor"));
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

                    @Override
                    public void cancelStrictNaturalUse(
                            ActionEnvelope envelope,
                            ActionCancellationReason reason) {
                        throw new AssertionError(
                                "Wheat fixture must not cancel a strict natural item use");
                    }
                };
        MinecraftWheatFarmingSkillNodeHandler.Resolver resolver =
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
                new SkillRuntimeBudget(2, 8, 8, 480));
        runtimeRef.set(runtime);
        registerHandler(runtime,
                WheatFarmingSkillIds.HARVEST_MATURE_WHEAT,
                new MinecraftWheatFarmingSkillNodeHandler(
                        MinecraftWheatFarmingSkillNodeHandler.Mode.HARVEST,
                        resolver, actions, signals));
        registerHandler(runtime,
                WheatFarmingSkillIds.PLANT_WHEAT,
                new MinecraftWheatFarmingSkillNodeHandler(
                        MinecraftWheatFarmingSkillNodeHandler.Mode.PLANT,
                        resolver, actions, signals));
        long tick = currentTick(bot);
        SkillRunSubmission submission = runtime.submit(new SkillRunRequest(
                bot.player().getUUID(),
                bot.player().runtimeHandle().generation(),
                WheatFarmingPlanCompiler.compile(
                        bot.player().getUUID(),
                        1L,
                        new BlockCoordinates(crop.getX(), crop.getY(),
                                crop.getZ())),
                tick));
        P2GameTestSupport.require(
                submission.status() == SkillRunSubmission.Status.ACCEPTED,
                "Wheat farming plan was rejected: " + submission);
        return new FarmingRuntime(runtime, submission.runId().orElseThrow(),
                bot);
    }

    private static void registerHandler(
            SkillRuntime runtime,
            io.github.greytaiwolf.botplayer.skill.core.SkillId id,
            MinecraftWheatFarmingSkillNodeHandler handler) {
        P2GameTestSupport.require(
                runtime.registerHandler(id, WheatFarmingSkillIds.VERSION,
                        handler) == SkillRuntime.HandlerRegistrationStatus
                                .REGISTERED,
                "Could not register wheat farming handler " + id);
    }

    private static void prepareMatureWheat(GameTestHelper helper) {
        helper.setBlock(RELATIVE_CROP.below(), Blocks.FARMLAND
                .defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
        helper.setBlock(RELATIVE_CROP, Blocks.WHEAT.defaultBlockState()
                .setValue(CropBlock.AGE, CropBlock.MAX_AGE));
        /* 保持耕地 moisture 指纹，避免 harvest 与 plant 之间的随机干涸造成测试噪声。 */
        helper.setBlock(new BlockPos(1, 0, 5), Blocks.WATER);
        /* GameTest 模板不能依赖天空光；把可复核的原版亮度固定在作物旁而不挡住视线。 */
        helper.setBlock(new BlockPos(1, 2, 5), Blocks.GLOWSTONE);
    }

    private static void prepareSeeds(TestBot bot, int count) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().getInventory().setItem(0,
                new ItemStack(Items.WHEAT_SEEDS, count));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void fillStorageLeavingOnlyThreeSlots(TestBot bot) {
        for (int inventorySlot = 1;
                inventorySlot <= 32;
                inventorySlot++) {
            bot.player().getInventory().setItem(inventorySlot,
                    new ItemStack(Items.DIRT, 1));
        }
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void requireNativeEmptyInventory(TestBot bot) {
        P2GameTestSupport.require(
                bot.player().containerMenu == bot.player().inventoryMenu
                        && bot.player().inventoryMenu.getCarried().isEmpty(),
                "Wheat farming left an open menu or non-empty cursor");
    }

    private static BlockPos cropPosition(GameTestHelper helper) {
        return helper.absolutePos(RELATIVE_CROP);
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

    private static boolean noHarvestDropsRemain(TestBot bot, BlockPos crop) {
        return bot.player().serverLevel().getEntitiesOfClass(
                ItemEntity.class,
                new AABB(crop.getX() - 2.0D,
                        crop.getY() - 2.0D,
                        crop.getZ() - 2.0D,
                        crop.getX() + 3.0D,
                        crop.getY() + 3.0D,
                        crop.getZ() + 3.0D),
                item -> item.getItem().is(Items.WHEAT)
                        || item.getItem().is(Items.WHEAT_SEEDS)).isEmpty();
    }

    private record FarmingRuntime(
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
