package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.action.StopAction;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.kernel.BotRuntimeHandle;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotActionTargetStatus;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleState;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleTransition;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.lifecycle.BotSnapshot;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game acceptance tests for generation ownership and the clientless player tick split.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P2LifecycleAcceptanceGameTests {
    private static final String TEMPLATE = "empty_9x5x9";
    private static final int DEATH_ITERATIONS = 100;

    private P2LifecycleAcceptanceGameTests() {}

    @GameTest(
            template = TEMPLATE,
            batch = "p2_lifecycle_respawn",
            timeoutTicks = 1800)
    public static void
            oneHundredDeathsReplaceBodiesAndCloseOldGenerations(
                    GameTestHelper helper) {
        prepareFloor(helper);
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        boolean autoRespawn = BotPlayerConfig.AUTO_RESPAWN.get();
        int respawnDelay =
                BotPlayerConfig.RESPAWN_DELAY_TICKS.get();
        boolean keepInventory = level.getGameRules().getBoolean(
                GameRules.RULE_KEEPINVENTORY);
        BotPlayerConfig.AUTO_RESPAWN.set(true);
        BotPlayerConfig.RESPAWN_DELAY_TICKS.set(0);
        level.getGameRules()
                .getRule(GameRules.RULE_KEEPINVENTORY)
                .set(true, server);

        LifecycleRun run = new LifecycleRun(
                helper,
                BotPlayerManagers.get(server),
                "P2Life100",
                autoRespawn,
                respawnDelay,
                keepInventory,
                helper.absoluteVec(
                        new Vec3(4.5D, 1.0D, 4.5D)));
        try {
            BotServerPlayer player = spawn(run, 0.0F);
            run.bind(player.runtimeHandle());
            awaitCondition(
                    run,
                    20,
                    player::onGround,
                    "initial bot never settled on the test floor",
                    () -> beginDeathIteration(run, player, 0));
        } catch (RuntimeException | AssertionError exception) {
            run.fail("setup failed: " + safeMessage(exception));
        }
    }

    @GameTest(
            template = TEMPLATE,
            batch = "p2_lifecycle_tick",
            timeoutTicks = 180)
    public static void clientlessConnectionPhaseRunsOncePerServerTick(
            GameTestHelper helper) {
        prepareFloor(helper);
        BotLifecycleManager manager =
                BotPlayerManagers.get(helper.getLevel().getServer());
        BotServerPlayer player = spawn(
                helper,
                manager,
                "P2TickPhase",
                helper.absoluteVec(
                        new Vec3(4.5D, 1.0D, 4.5D)),
                0.0F);
        awaitCondition(
                helper,
                20,
                player::onGround,
                "Bot never settled before tick-phase verification",
                () -> {
                    try {
                        int before = player.tickCount;
                        player.tickClientlessConnectionPhase();
                        int afterFirst = player.tickCount;
                        player.tickClientlessConnectionPhase();
                        require(
                                afterFirst == before
                                        || afterFirst
                                                == before + 1,
                                "The guarded connection phase advanced more than one entity tick");
                        require(
                                player.tickCount == afterFirst,
                                "The connection phase advanced twice in one server tick");

                        int startingServerTick =
                                helper.getLevel()
                                        .getServer()
                                        .getTickCount();
                        int startingEntityTick =
                                player.tickCount;
                        double startingZ = player.getZ();
                        ActionTicket action = submit(
                                manager,
                                player,
                                new MoveInputAction(
                                        1.0F,
                                        0.0F,
                                        false,
                                        false,
                                        false,
                                        12,
                                        6),
                                60,
                                "tick-phase");
                        awaitOutcome(
                                helper,
                                action.completion(),
                                outcome -> {
                                    try {
                                        requireState(
                                                outcome,
                                                ActionState.SUCCEEDED,
                                                ActionFailureCode.NONE);
                                        /*
                                         * GameTest callbacks can run before ServerTickEvent.Post.
                                         * Bring the current absolute tick to its guarded connection
                                         * phase, then compare the complete interval.
                                         */
                                        player.tickClientlessConnectionPhase();
                                        int serverTicks =
                                                helper.getLevel()
                                                                .getServer()
                                                                .getTickCount()
                                                        - startingServerTick;
                                        int entityTicks =
                                                player.tickCount
                                                        - startingEntityTick;
                                        require(
                                                entityTicks
                                                        == serverTicks,
                                                "Entity tick delta "
                                                        + entityTicks
                                                        + " differed from server tick delta "
                                                        + serverTicks);
                                        require(
                                                player.getZ()
                                                        > startingZ
                                                                + 0.05D,
                                                "Connection-phase input produced no physical movement");
                                        assertInputClear(player);
                                        remove(
                                                manager,
                                                "P2TickPhase",
                                                "P2 tick-phase GameTest complete");
                                        helper.succeed();
                                    } catch (RuntimeException
                                            | AssertionError
                                                    exception) {
                                        remove(
                                                manager,
                                                "P2TickPhase",
                                                "P2 tick-phase GameTest failed");
                                        helper.fail(safeMessage(
                                                exception));
                                    }
                                });
                    } catch (RuntimeException
                            | AssertionError exception) {
                        remove(
                                manager,
                                "P2TickPhase",
                                "P2 tick-phase GameTest failed");
                        helper.fail(safeMessage(exception));
                    }
                });
    }

    private static void beginDeathIteration(
            LifecycleRun run,
            BotServerPlayer player,
            int iteration) {
        try {
            require(
                    player.runtimeHandle() == run.handle(),
                    "iteration "
                            + iteration
                            + " replaced the stable runtime handle");
            require(
                    run.manager()
                                    .resolveActionTarget(
                                            player.getUUID(),
                                            player.runtimeHandle()
                                                    .generation())
                                    .orElse(null)
                            == player,
                    "iteration "
                            + iteration
                            + " did not start from an authoritative body");
            prepareBody(run, player);

            long generation =
                    player.runtimeHandle().generation();
            ActionTicket movement = submit(
                    run.manager(),
                    player,
                    new MoveInputAction(
                            1.0F,
                            0.0F,
                            false,
                            false,
                            false,
                            80,
                            20),
                    140,
                    "life-" + iteration + "-move");
            ActionTicket heldUse = submit(
                    run.manager(),
                    player,
                    new WorldInteractionAction(
                            new WorldInteractionActionSpec.UseItem(
                                    WorldInteractionActionSpec.Hand
                                            .MAIN_HAND,
                                    MinecraftActionSnapshot
                                            .selectedItem(player),
                                    WorldInteractionActionSpec
                                            .ItemUseMode
                                            .RELEASE_AFTER_HOLD,
                                    80)),
                    140,
                    "life-" + iteration + "-use");

            awaitCondition(
                    run,
                    24,
                    () -> player.zza > 0.0F
                            && player.isUsingItem(),
                    "iteration "
                            + iteration
                            + " actions never reached movement and held-use",
                    () -> killAndAwaitReplacement(
                            run,
                            player,
                            generation,
                            movement,
                            heldUse,
                            iteration));
        } catch (RuntimeException | AssertionError exception) {
            run.fail(
                    "iteration "
                            + iteration
                            + " setup failed: "
                            + safeMessage(exception));
        }
    }

    private static void killAndAwaitReplacement(
            LifecycleRun run,
            BotServerPlayer oldPlayer,
            long oldGeneration,
            ActionTicket movement,
            ActionTicket heldUse,
            int iteration) {
        try {
            oldPlayer.kill();
            require(
                    snapshot(run.manager(), oldPlayer.getUUID())
                                    .state()
                            == BotLifecycleState.DEAD,
                    "iteration "
                            + iteration
                            + " did not enter DEAD synchronously");
            awaitCondition(
                    run,
                    20,
                    () -> run.manager()
                                    .resolveActionTarget(
                                            oldPlayer.getUUID(),
                                            oldGeneration + 1L)
                                    .filter(player ->
                                            player != oldPlayer)
                                    .isPresent(),
                    "iteration "
                            + iteration
                            + " never obtained an authoritative replacement",
                    () -> {
                        BotServerPlayer replacement =
                                run.manager()
                                        .resolveActionTarget(
                                                oldPlayer.getUUID(),
                                                oldGeneration
                                                        + 1L)
                                        .orElseThrow();
                        awaitCondition(
                                run,
                                20,
                                () -> movement.completion()
                                                .toCompletableFuture()
                                                .isDone()
                                        && heldUse
                                                .completion()
                                                .toCompletableFuture()
                                                .isDone(),
                                "iteration "
                                        + iteration
                                        + " cancellation completions did not arrive",
                                () -> verifyReplacement(
                                        run,
                                        oldPlayer,
                                        replacement,
                                        oldGeneration,
                                        movement,
                                        heldUse,
                                        iteration));
                    });
        } catch (RuntimeException | AssertionError exception) {
            run.fail(
                    "iteration "
                            + iteration
                            + " death failed: "
                            + safeMessage(exception));
        }
    }

    private static void verifyReplacement(
            LifecycleRun run,
            BotServerPlayer oldPlayer,
            BotServerPlayer replacement,
            long oldGeneration,
            ActionTicket movement,
            ActionTicket heldUse,
            int iteration) {
        try {
            UUID botId = oldPlayer.getUUID();
            require(
                    replacement.runtimeHandle()
                                    == run.handle(),
                    "iteration "
                            + iteration
                            + " replacement changed runtime handle");
            require(
                    run.handle().generation()
                            == oldGeneration + 1L,
                    "iteration "
                            + iteration
                            + " generation did not advance exactly once");
            require(
                    replacement.connection != null
                            && replacement.connection.player
                                    == replacement,
                    "iteration "
                            + iteration
                            + " listener did not own replacement");
            require(
                    run.helper()
                                    .getLevel()
                                    .getServer()
                                    .getPlayerList()
                                    .getPlayer(botId)
                            == replacement,
                    "iteration "
                            + iteration
                            + " PlayerList did not own replacement");
            for (ServerLevel level : run.helper()
                    .getLevel()
                    .getServer()
                    .getAllLevels()) {
                require(
                        level.getPlayerByUUID(botId)
                                != oldPlayer,
                        "iteration "
                                + iteration
                                + " old body remained in "
                                + level.dimension().location());
            }
            require(
                    run.manager()
                                    .inspectActionTarget(
                                            botId,
                                            oldGeneration)
                                    .status()
                            == BotActionTargetStatus
                                    .STALE_GENERATION,
                    "iteration "
                            + iteration
                            + " old generation was not stale");
            require(
                    run.manager()
                            .resolveCleanupTarget(
                                    botId, oldGeneration)
                            .isEmpty(),
                    "iteration "
                            + iteration
                            + " old generation retained cleanup authority");

            requireState(
                    join(movement.completion()),
                    ActionState.CANCELLED,
                    ActionFailureCode.CANCELLED);
            requireState(
                    join(heldUse.completion()),
                    ActionState.CANCELLED,
                    ActionFailureCode.CANCELLED);
            assertInputClear(oldPlayer);
            assertInputClear(replacement);
            require(
                    !oldPlayer.isUsingItem()
                            && !replacement.isUsingItem(),
                    "iteration "
                            + iteration
                            + " retained held-use state");
            assertTransitionTriplet(
                    run.manager(),
                    botId,
                    oldGeneration,
                    oldGeneration + 1L,
                    iteration);
            assertOldGenerationClosed(
                    run.manager(),
                    botId,
                    oldGeneration,
                    iteration);

            if (iteration + 1 == DEATH_ITERATIONS) {
                run.succeed();
                return;
            }
            beginDeathIteration(
                    run, replacement, iteration + 1);
        } catch (RuntimeException | AssertionError exception) {
            run.fail(
                    "iteration "
                            + iteration
                            + " verification failed: "
                            + safeMessage(exception));
        }
    }

    private static void assertTransitionTriplet(
            BotLifecycleManager manager,
            UUID botId,
            long oldGeneration,
            long newGeneration,
        int iteration) {
        List<BotLifecycleTransition> transitions =
                manager.lifecycleTransitionHistory(256).stream()
                        .filter(transition ->
                                transition.botId()
                                        .equals(botId))
                        .toList();
        require(
                transitions.size() >= 3,
                "iteration "
                        + iteration
                        + " did not emit three lifecycle transitions");
        int offset = transitions.size() - 3;
        assertTransition(
                transitions.get(offset),
                botId,
                oldGeneration,
                BotLifecycleState.ACTIVE,
                BotLifecycleState.DEAD,
                iteration);
        assertTransition(
                transitions.get(offset + 1),
                botId,
                oldGeneration,
                BotLifecycleState.DEAD,
                BotLifecycleState.RESPAWNING,
                iteration);
        assertTransition(
                transitions.get(offset + 2),
                botId,
                newGeneration,
                BotLifecycleState.RESPAWNING,
                BotLifecycleState.ACTIVE,
                iteration);
    }

    private static void assertTransition(
            BotLifecycleTransition transition,
            UUID botId,
            long generation,
            BotLifecycleState previous,
            BotLifecycleState current,
            int iteration) {
        require(
                transition.botId().equals(botId)
                        && transition.generation()
                                == generation
                        && transition.previous() == previous
                        && transition.current() == current,
                "iteration "
                        + iteration
                        + " had unexpected lifecycle transition "
                        + transition);
    }

    private static void assertOldGenerationClosed(
            BotLifecycleManager manager,
            UUID botId,
            long oldGeneration,
            int iteration) {
        long currentTick = manager
                .resolveActionTarget(
                        botId, oldGeneration + 1L)
                .orElseThrow()
                .serverLevel()
                .getServer()
                .getTickCount();
        UUID actionId = UUID.randomUUID();
        ActionEnvelope staleEnvelope = new ActionEnvelope(
                actionId,
                botId,
                oldGeneration,
                "gametest/lifecycle/stale/"
                        + iteration
                        + "/"
                        + actionId,
                currentTick + 20L,
                5,
                new StopAction(),
                ActionOrigin.none());
        ActionMailbox.Submission stale =
                manager.submitAction(
                        staleEnvelope,
                        ActionPriority.OWNER_TASK);
        require(
                stale.status()
                                == ActionMailbox
                                        .SubmissionStatus
                                        .BOT_GENERATION_CLOSED
                        && stale.completion().isEmpty(),
                "iteration "
                        + iteration
                        + " accepted an old-generation action");
    }

    private static void prepareBody(
            LifecycleRun run, BotServerPlayer player) {
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.getInventory().clearContent();
        player.getInventory().selected = 0;
        player.getInventory()
                .setItem(0, new ItemStack(Items.SHIELD));
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        player.setJumping(false);
        player.stopUsingItem();
        Vec3 position = run.spawnPosition();
        player.teleportTo(
                run.helper().getLevel(),
                position.x,
                position.y,
                position.z,
                Set.of(),
                0.0F,
                0.0F);
        player.setYHeadRot(0.0F);
    }

    private static BotServerPlayer spawn(
            LifecycleRun run, float yaw) {
        return spawn(
                run.helper(),
                run.manager(),
                run.name(),
                run.spawnPosition(),
                yaw);
    }

    private static BotServerPlayer spawn(
            GameTestHelper helper,
            BotLifecycleManager manager,
            String name,
            Vec3 position,
            float yaw) {
        ServerLevel level = helper.getLevel();
        BotServerPlayer player = manager.spawn(
                level.getServer()
                        .createCommandSourceStack()
                        .withLevel(level)
                        .withPosition(position)
                        .withRotation(new Vec2(0.0F, yaw)),
                name);
        player.setGameMode(GameType.SURVIVAL);
        player.setHealth(player.getMaxHealth());
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        player.setJumping(false);
        player.stopUsingItem();
        player.teleportTo(
                level,
                position.x,
                position.y,
                position.z,
                Set.of(),
                yaw,
                0.0F);
        player.setYHeadRot(yaw);
        return player;
    }

    private static ActionTicket submit(
            BotLifecycleManager manager,
            BotServerPlayer player,
            ActionRequest request,
            int maxTicks,
            String key) {
        long currentTick =
                player.serverLevel()
                        .getServer()
                        .getTickCount();
        UUID actionId = UUID.randomUUID();
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                player.getUUID(),
                player.runtimeHandle().generation(),
                "gametest/lifecycle/" + key + "/" + actionId,
                currentTick + maxTicks + 40L,
                maxTicks,
                request,
                ActionOrigin.none());
        ActionMailbox.Submission submission =
                manager.submitAction(
                        envelope, ActionPriority.OWNER_TASK);
        require(
                submission.status()
                        == ActionMailbox.SubmissionStatus.ENQUEUED,
                "action "
                        + key
                        + " was rejected: "
                        + submission.status());
        return new ActionTicket(
                envelope,
                submission.completion().orElseThrow());
    }

    private static void awaitCondition(
            LifecycleRun run,
            int remainingTicks,
            BooleanSupplier condition,
            String failure,
            Runnable continuation) {
        try {
            if (condition.getAsBoolean()) {
                continuation.run();
                return;
            }
            if (remainingTicks <= 0) {
                run.fail(failure);
                return;
            }
            run.helper()
                    .runAfterDelay(
                            1L,
                            () -> awaitCondition(
                                    run,
                                    remainingTicks - 1,
                                    condition,
                                    failure,
                                    continuation));
        } catch (RuntimeException | AssertionError exception) {
            run.fail(failure + ": " + safeMessage(exception));
        }
    }

    private static void awaitCondition(
            GameTestHelper helper,
            int remainingTicks,
            BooleanSupplier condition,
            String failure,
            Runnable continuation) {
        if (condition.getAsBoolean()) {
            continuation.run();
            return;
        }
        if (remainingTicks <= 0) {
            helper.fail(failure);
            return;
        }
        helper.runAfterDelay(
                1L,
                () -> awaitCondition(
                        helper,
                        remainingTicks - 1,
                        condition,
                        failure,
                        continuation));
    }

    private static void awaitOutcome(
            GameTestHelper helper,
            CompletionStage<ActionOutcome> completion,
            java.util.function.Consumer<ActionOutcome> verifier) {
        CompletableFuture<ActionOutcome> future =
                completion.toCompletableFuture();
        if (!future.isDone()) {
            helper.runAfterDelay(
                    1L,
                    () -> awaitOutcome(
                            helper, completion, verifier));
            return;
        }
        try {
            verifier.accept(future.join());
        } catch (CompletionException exception) {
            helper.fail(
                    "Action completion failed: "
                            + safeMessage(exception));
        }
    }

    private static ActionOutcome join(
            CompletionStage<ActionOutcome> completion) {
        try {
            return completion.toCompletableFuture().join();
        } catch (CompletionException exception) {
            throw new IllegalStateException(
                    "Action completion failed",
                    exception.getCause() == null
                            ? exception
                            : exception.getCause());
        }
    }

    private static BotSnapshot snapshot(
            BotLifecycleManager manager, UUID botId) {
        return manager.snapshots().stream()
                .filter(candidate ->
                        candidate.botId().equals(botId))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Bot runtime snapshot disappeared"));
    }

    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(
                        new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 5; y++) {
                    helper.setBlock(
                            new BlockPos(x, y, z),
                            Blocks.AIR);
                }
            }
        }
    }

    private static void assertInputClear(
            BotServerPlayer player) {
        require(
                player.xxa == 0.0F
                        && player.yya == 0.0F
                        && player.zza == 0.0F
                        && !player.isShiftKeyDown()
                        && !player.isSprinting(),
                "Bot retained movement input");
    }

    private static void requireState(
            ActionOutcome outcome,
            ActionState expectedState,
            ActionFailureCode expectedFailure) {
        require(
                outcome.state() == expectedState,
                "Expected action state "
                        + expectedState
                        + " but got "
                        + outcome.state()
                        + ": "
                        + outcome.safeSummary());
        require(
                outcome.failureCode() == expectedFailure,
                "Expected failure "
                        + expectedFailure
                        + " but got "
                        + outcome.failureCode());
    }

    private static void remove(
            BotLifecycleManager manager,
            String name,
            String reason) {
        manager.removeByName(name, Component.literal(reason));
    }

    private static String safeMessage(Throwable throwable) {
        Throwable cause =
                throwable instanceof CompletionException
                                && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
        return cause.getMessage() == null
                ? cause.toString()
                : cause.getMessage();
    }

    private static void require(
            boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record ActionTicket(
            ActionEnvelope envelope,
            CompletionStage<ActionOutcome> completion) {}

    private static final class LifecycleRun {
        private final GameTestHelper helper;
        private final BotLifecycleManager manager;
        private final String name;
        private final boolean originalAutoRespawn;
        private final int originalRespawnDelay;
        private final boolean originalKeepInventory;
        private final Vec3 spawnPosition;
        private BotRuntimeHandle handle;
        private boolean finished;

        private LifecycleRun(
                GameTestHelper helper,
                BotLifecycleManager manager,
                String name,
                boolean originalAutoRespawn,
                int originalRespawnDelay,
                boolean originalKeepInventory,
                Vec3 spawnPosition) {
            this.helper = helper;
            this.manager = manager;
            this.name = name;
            this.originalAutoRespawn = originalAutoRespawn;
            this.originalRespawnDelay = originalRespawnDelay;
            this.originalKeepInventory =
                    originalKeepInventory;
            this.spawnPosition = spawnPosition;
        }

        private GameTestHelper helper() {
            return helper;
        }

        private BotLifecycleManager manager() {
            return manager;
        }

        private String name() {
            return name;
        }

        private Vec3 spawnPosition() {
            return spawnPosition;
        }

        private void bind(BotRuntimeHandle runtimeHandle) {
            if (handle != null && handle != runtimeHandle) {
                throw new IllegalStateException(
                        "Lifecycle test handle changed");
            }
            handle = runtimeHandle;
        }

        private BotRuntimeHandle handle() {
            if (handle == null) {
                throw new IllegalStateException(
                        "Lifecycle test has no runtime handle");
            }
            return handle;
        }

        private void succeed() {
            if (finished) {
                return;
            }
            finished = true;
            cleanup("P2 lifecycle GameTest complete");
            helper.succeed();
        }

        private void fail(String message) {
            if (!finished) {
                finished = true;
                cleanup("P2 lifecycle GameTest failed");
            }
            helper.fail(message);
        }

        private void cleanup(String reason) {
            try {
                manager.removeByName(
                        name, Component.literal(reason));
            } finally {
                BotPlayerConfig.AUTO_RESPAWN.set(
                        originalAutoRespawn);
                BotPlayerConfig.RESPAWN_DELAY_TICKS.set(
                        originalRespawnDelay);
                helper.getLevel()
                        .getGameRules()
                        .getRule(
                                GameRules
                                        .RULE_KEEPINVENTORY)
                        .set(
                                originalKeepInventory,
                                helper.getLevel().getServer());
            }
        }
    }
}
