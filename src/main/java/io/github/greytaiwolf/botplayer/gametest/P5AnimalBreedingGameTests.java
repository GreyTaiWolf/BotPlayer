package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionCancellationReason;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.builtin.breeding.VanillaCowBreeding;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillRegistry;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanLimits;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanValidator;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftCowBreedingSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.ActionBackedSkillNodeHandler;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunRequest;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntime;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRuntimeBudget;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5B 原版动物繁殖验收。
 *
 * <p>夹具只生成两头成年原版牛和两份小麦；成功、冷却与幼体均必须来自
 * {@code INTERACT_ENTITY -> vanilla Animal/BreedGoal}。测试不设置 age/love，不生成 child，
 * 也不直接写入 bot 背包。局部 {@link SkillRuntime} 只是将独立 handler 显式接到现有生产
 * action mailbox，避免本阶段改动共享 lifecycle 注册表。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5AnimalBreedingGameTests {
    private static final String BATCH = "p5_animal_breeding";
    private static final int TIMEOUT_TICKS = 420;
    private static final Vec3 FIRST_COW_POSITION =
            new Vec3(4.15D, 1.0D, 5.85D);
    private static final Vec3 SECOND_COW_POSITION =
            new Vec3(4.95D, 1.0D, 5.85D);

    private P5AnimalBreedingGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void twoRealEntityFeedsProduceVanillaBabyAndCooldown(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "vanilla_cow_breeding_success");
        TestBot bot = fixture.spawn("breed");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Cow first = adultCow(helper, FIRST_COW_POSITION);
            Cow second = adultCow(helper, SECOND_COW_POSITION);
            cleanup.add(first::discard);
            cleanup.add(second::discard);
            giveExactWheat(bot, 2);

            BreedingRun run = startRun(bot, first, second);
            cleanup.add(run.runtime()::close);
            boolean[] firstFeedObserved = {false};
            driveUntilTerminal(
                    helper,
                    bot,
                    run,
                    cleanup,
                    320,
                    view -> {
                        if (!firstFeedObserved[0]
                                && view.completedNodes() == 1) {
                            P2GameTestSupport.require(
                                    first.isInLove()
                                            && first.getLoveCause()
                                                    == bot.player()
                                            && !second.isInLove(),
                                    "First cow feed was not proven through vanilla love state");
                            firstFeedObserved[0] = true;
                        }
                    },
                    terminal -> {
                        P2GameTestSupport.require(
                                terminal.state() == SkillRunState.SUCCEEDED,
                                "Vanilla cow breeding run did not succeed: "
                                        + terminal.safeSummary());
                        P2GameTestSupport.require(firstFeedObserved[0],
                                "Second feed started before first vanilla feed was observed");
                        P2GameTestSupport.require(
                                !first.isInLove()
                                        && !second.isInLove()
                                        && first.getAge() > 0
                                        && second.getAge() > 0,
                                "Successful breeding did not leave both parents in vanilla cooldown");
                        P2GameTestSupport.require(
                                bot.player().getInventory().selected == 0
                                        && bot.player().getInventory()
                                                        .getItem(0).isEmpty(),
                                "Two real feeds did not debit exactly two selected wheat");
                        Cow child = freshBaby(bot, first, second).orElseThrow(
                                () -> new IllegalStateException(
                                        "No new vanilla cow child was observed after the two feeds"));
                        cleanup.add(child::discard);
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
    public static void cancellationBeforeMailboxDispatchLeavesCowsAndWheatUntouched(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "vanilla_cow_breeding_cancel");
        TestBot bot = fixture.spawn("breed");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Cow first = adultCow(helper, FIRST_COW_POSITION);
            Cow second = adultCow(helper, SECOND_COW_POSITION);
            cleanup.add(first::discard);
            cleanup.add(second::discard);
            giveExactWheat(bot, 2);
            BreedingRun run = startRun(bot, first, second);
            cleanup.add(run.runtime()::close);

            long currentTick = currentTick(bot);
            run.runtime().tick(currentTick);
            P2GameTestSupport.require(
                    run.runtime().cancel(
                            run.runId(), currentTick,
                            "P5 breeding cancellation GameTest")
                                    == SkillRuntime.CancelStatus.CANCELLED,
                    "Queued breeding run did not accept cancellation");
            helper.runAfterDelay(5L, () -> {
                try {
                    SkillRunView terminal = run.runtime().inspectRun(
                            run.runId()).orElseThrow(() ->
                                    new IllegalStateException(
                                            "Cancelled breeding run lost its terminal view"));
                    P2GameTestSupport.require(
                            terminal.state() == SkillRunState.CANCELLED,
                            "Cancelled breeding run reached an unexpected state");
                    P2GameTestSupport.require(
                            bot.player().getInventory().getItem(0).is(Items.WHEAT)
                                    && bot.player().getInventory()
                                                    .getItem(0).getCount() == 2
                                    && !first.isInLove()
                                    && !second.isInLove()
                                    && freshBaby(bot, first, second).isEmpty(),
                            "Cancellation allowed a queued feed, love state, or child mutation");
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

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void partnerDisappearingAfterFirstFeedFailsBeforeSecondDebit(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "vanilla_cow_breeding_partner_drift");
        TestBot bot = fixture.spawn("breed");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Cow first = adultCow(helper, FIRST_COW_POSITION);
            Cow second = adultCow(helper, SECOND_COW_POSITION);
            cleanup.add(first::discard);
            cleanup.add(second::discard);
            giveExactWheat(bot, 2);
            BreedingRun run = startRun(bot, first, second);
            cleanup.add(run.runtime()::close);
            boolean[] partnerDiscarded = {false};

            driveUntilTerminal(
                    helper,
                    bot,
                    run,
                    cleanup,
                    160,
                    view -> {
                        if (!partnerDiscarded[0]
                                && view.completedNodes() == 1) {
                            /* Fixture-induced world drift; no child/age/love state is fabricated. */
                            second.discard();
                            partnerDiscarded[0] = true;
                        }
                    },
                    terminal -> {
                        P2GameTestSupport.require(partnerDiscarded[0],
                                "Partner drift injection never ran after the first feed");
                        P2GameTestSupport.require(
                                terminal.state() == SkillRunState.FAILED
                                        && terminal.failureCode().orElseThrow()
                                                == SkillFailureCode.TARGET_GONE,
                                "Partner disappearance did not fail closed as TARGET_GONE: "
                                        + terminal.safeSummary());
                        P2GameTestSupport.require(
                                bot.player().getInventory().getItem(0).is(Items.WHEAT)
                                        && bot.player().getInventory()
                                                        .getItem(0).getCount() == 1
                                        && first.isInLove()
                                        && freshBaby(bot, first, second).isEmpty(),
                                "Partner drift dispatched a second feed or fabricated a child");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * This exercises the breeding handler's frozen count fingerprint.  If the
     * handler silently used the legacy item-unconstrained overload, the one
     * remaining wheat would still feed the first cow; the strict mailbox
     * contract instead rejects it before vanilla receives the interaction.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void breedingSkillHeldCountDriftFailsBeforeFirstFeed(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "vanilla_cow_breeding_skill_held_count_drift");
        TestBot bot = fixture.spawn("breed");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Cow first = adultCow(helper, FIRST_COW_POSITION);
            Cow second = adultCow(helper, SECOND_COW_POSITION);
            cleanup.add(first::discard);
            cleanup.add(second::discard);
            giveExactWheat(bot, 2);
            BreedingRun run = startRun(bot, first, second);
            cleanup.add(run.runtime()::close);

            run.runtime().tick(currentTick(bot));
            /* Controlled drift after the handler froze its exact count. */
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.WHEAT, 1));
            helper.runAfterDelay(1L, () -> driveUntilTerminal(
                    helper,
                    bot,
                    run,
                    cleanup,
                    120,
                    ignored -> {
                    },
                    terminal -> {
                        P2GameTestSupport.require(
                                terminal.state() == SkillRunState.FAILED
                                        && terminal.failureCode().orElseThrow()
                                                == SkillFailureCode.WORLD_CHANGED,
                                "Held-count drift was not surfaced as a fail-closed skill failure: "
                                        + terminal.safeSummary());
                        P2GameTestSupport.require(
                                bot.player().getInventory().getItem(0).is(Items.WHEAT)
                                        && bot.player().getInventory()
                                                        .getItem(0).getCount() == 1
                                        && !first.isInLove()
                                        && !second.isInLove()
                                        && first.getAge() == 0
                                        && second.getAge() == 0
                                        && freshBaby(bot, first, second).isEmpty(),
                                "Held-count drift reached vanilla feeding, cooldown, or child creation");
                    }));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * The action contract must reject a stack swapped after the mailbox has
     * accepted a strict entity-interaction envelope, rather than dispatching a
     * generic right click and relying on the skill's postcondition to notice.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void entityFeedHeldItemMismatchFailsBeforeVanillaInteraction(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "vanilla_cow_breeding_held_item_drift");
        TestBot bot = fixture.spawn("breed");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Cow target = adultCow(helper, FIRST_COW_POSITION);
            cleanup.add(target::discard);
            giveExactWheat(bot, 1);
            EntityTargetFingerprint targetFingerprint =
                    MinecraftActionSnapshot.entity(bot.player(), target);
            ItemStackFingerprint expectedWheat =
                    MinecraftActionSnapshot.selectedItem(bot.player());
            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .InteractEntity(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            targetFingerprint,
                                            Optional.empty(),
                                            expectedWheat)),
                            40);

            /* Controlled fixture drift after enqueue, before the mailbox tick. */
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.WHEAT, 2));
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    80,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.FAILED
                                            && outcome.failureCode()
                                                    == ActionFailureCode
                                                            .PRECONDITION_FAILED,
                                    "Held-item mismatch was not rejected before INTERACT_ENTITY dispatch: "
                                            + outcome.safeSummary());
                            P2GameTestSupport.require(
                                    !target.isInLove()
                                            && target.getAge() == 0
                                            && bot.player().getInventory()
                                                    .getItem(0).is(Items.WHEAT)
                                            && bot.player().getInventory()
                                                            .getItem(0).getCount()
                                                    == 2,
                                    "Rejected held-item mismatch changed cow love/age or the drifted inventory");
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

    private static Cow adultCow(
            GameTestHelper helper, Vec3 relativePosition) {
        Cow cow = helper.spawn(EntityType.COW, relativePosition);
        cow.setPersistenceRequired();
        return cow;
    }

    private static void giveExactWheat(TestBot bot, int count) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().getInventory().setItem(
                0, new ItemStack(Items.WHEAT, count));
    }

    private static BreedingRun startRun(
            TestBot bot, Cow first, Cow second) {
        SkillRegistry registry = new SkillRegistry();
        P2GameTestSupport.require(
                registry.register(VanillaCowBreeding.descriptor())
                        == SkillRegistry.RegisterStatus.REGISTERED,
                "Standalone breeding descriptor could not register");
        SkillRuntime runtime = new SkillRuntime(
                registry,
                new SkillPlanValidator(registry, SkillPlanLimits.defaults()),
                new ResourceReservationService(16, 240),
                new SkillRuntimeBudget(1, 8, 16, 720));
        MinecraftCowBreedingSkillNodeHandler handler =
                new MinecraftCowBreedingSkillNodeHandler(
                        bot.manager()::resolveActive,
                        actionGateway(bot),
                        runtime::offerSignal);
        P2GameTestSupport.require(
                runtime.registerHandler(
                                VanillaCowBreeding.ID,
                                VanillaCowBreeding.VERSION,
                                handler)
                        == SkillRuntime.HandlerRegistrationStatus.REGISTERED,
                "Standalone breeding handler could not register");
        SkillPlan plan = VanillaCowBreeding.compile(
                bot.player().getUUID(),
                1L,
                new VanillaCowBreeding.CowPairRequest(
                        first.getUUID(), second.getUUID(), 0));
        SkillRunSubmission submission = runtime.submit(new SkillRunRequest(
                bot.player().getUUID(),
                bot.player().runtimeHandle().generation(),
                plan,
                currentTick(bot)));
        P2GameTestSupport.require(
                submission.status() == SkillRunSubmission.Status.ACCEPTED,
                "Standalone breeding plan was not accepted: "
                        + submission.safeSummary());
        return new BreedingRun(runtime, submission.runId().orElseThrow());
    }

    private static ActionBackedSkillNodeHandler.ActionGateway actionGateway(
            TestBot bot) {
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
                throw new AssertionError(
                        "Animal fixture must not cancel a strict natural item use");
            }
        };
    }

    private static void driveUntilTerminal(
            GameTestHelper helper,
            TestBot bot,
            BreedingRun run,
            P2GameTestSupport.Cleanup cleanup,
            int remainingTicks,
            ViewHook hook,
            TerminalVerifier verifier) {
        try {
            run.runtime().tick(currentTick(bot));
            SkillRunView view = run.runtime().inspectRun(run.runId())
                    .orElseThrow(() -> new IllegalStateException(
                            "Breeding runtime lost its run view"));
            hook.observe(view);
            if (view.state().isTerminal()) {
                verifier.verify(view);
                cleanup.run();
                helper.succeed();
                return;
            }
            if (remainingTicks <= 0) {
                cleanup.run();
                helper.fail("Breeding runtime did not reach a terminal state");
                return;
            }
            helper.runAfterDelay(1L, () -> driveUntilTerminal(
                    helper,
                    bot,
                    run,
                    cleanup,
                    remainingTicks - 1,
                    hook,
                    verifier));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            helper.fail(message(exception));
        }
    }

    private static Optional<Cow> freshBaby(
            TestBot bot, Cow first, Cow second) {
        List<Cow> nearby = bot.player().serverLevel().getEntitiesOfClass(
                Cow.class,
                bot.player().getBoundingBox().inflate(8.0D),
                candidate -> candidate.getClass() == Cow.class
                        && candidate.isAlive()
                        && !candidate.isRemoved());
        return nearby.stream()
                .filter(candidate -> !candidate.getUUID().equals(
                        first.getUUID()))
                .filter(candidate -> !candidate.getUUID().equals(
                        second.getUUID()))
                .filter(candidate -> candidate.isBaby()
                        && candidate.getAge() < 0)
                .findFirst();
    }

    private static long currentTick(TestBot bot) {
        return bot.player().serverLevel().getServer().getTickCount();
    }

    private static String message(Throwable exception) {
        return exception.getMessage() == null
                ? exception.toString()
                : exception.getMessage();
    }

    private record BreedingRun(SkillRuntime runtime, UUID runId) {
    }

    @FunctionalInterface
    private interface ViewHook {
        void observe(SkillRunView view);
    }

    @FunctionalInterface
    private interface TerminalVerifier {
        void verify(SkillRunView view);
    }
}
