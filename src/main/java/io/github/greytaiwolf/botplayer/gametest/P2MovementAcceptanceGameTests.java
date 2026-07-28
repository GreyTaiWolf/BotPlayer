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
import io.github.greytaiwolf.botplayer.action.JumpAction;
import io.github.greytaiwolf.botplayer.action.MoveInputAction;
import io.github.greytaiwolf.botplayer.kernel.BotConnection;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game acceptance tests for the P2-B physical input bridge.
 *
 * <p>These tests intentionally assert world motion, pose, collision, and virtual-connection
 * telemetry. A successful action outcome without those physical facts is not acceptance.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P2MovementAcceptanceGameTests {
    private static final String TEMPLATE = "empty_9x5x9";
    private static final int TEST_TIMEOUT_TICKS = 240;
    private static final int OUTCOME_WAIT_TICKS = 180;
    private static final double POSITION_EPSILON = 1.0E-3D;

    private P2MovementAcceptanceGameTests() {}

    @GameTest(
            template = TEMPLATE,
            batch = "p2_movement_jump",
            timeoutTicks = TEST_TIMEOUT_TICKS)
    public static void oneBlockObstacleJumpUsesVanillaPhysics(
            GameTestHelper helper) {
        prepareFloor(helper);
        BlockPos obstacle = new BlockPos(4, 1, 5);
        helper.setBlock(obstacle, Blocks.STONE);

        TestBot bot = spawn(
                helper,
                "P2Jump",
                new Vec3(4.5D, 1.0D, 4.15D),
                0.0F);
        awaitCondition(
                helper,
                bot,
                20,
                () -> hasFreshGroundContact(bot),
                "Bot did not settle on the test floor",
                () -> {
                    double obstacleBack =
                            helper.absolutePos(obstacle).getZ() + 1.0D;
                    CompletionStage<ActionOutcome> completion = submit(
                            bot,
                            new JumpAction(1.0F, 0.0F, true, 10),
                            60);
                    awaitOutcome(
                            helper,
                            bot,
                            completion,
                            () -> {},
                            outcome -> {
                                requireState(
                                        outcome,
                                        ActionState.SUCCEEDED,
                                        ActionFailureCode.NONE);
                                require(
                                        Boolean.parseBoolean(evidence(
                                                outcome,
                                                "jump.left_ground")),
                                        "Jump never left the ground");
                                assertTerminalInputClear(bot.player());
                                awaitCondition(
                                        helper,
                                        bot,
                                        20,
                                        () -> bot.player()
                                                                .getBoundingBox()
                                                                .minZ
                                                        > obstacleBack
                                                                - POSITION_EPSILON,
                                        "Bot did not physically clear the one-block obstacle",
                                        () -> {
                                            assertNoTeleportAcknowledgement(
                                                    bot);
                                            removeAndSucceed(helper, bot);
                                        });
                            });
                });
    }

    @GameTest(
            template = TEMPLATE,
            batch = "p2_movement_sneak",
            timeoutTicks = TEST_TIMEOUT_TICKS)
    public static void stationarySneakProducesCrouchingPose(
            GameTestHelper helper) {
        prepareFloor(helper);
        TestBot bot = spawn(
                helper,
                "P2Sneak",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        awaitCondition(
                helper,
                bot,
                20,
                () -> hasFreshGroundContact(bot),
                "Bot did not settle on the test floor",
                () -> {
                    boolean[] observedCrouching = {false};
                    CompletionStage<ActionOutcome> completion = submit(
                            bot,
                            new MoveInputAction(
                                    0.0F,
                                    0.0F,
                                    false,
                                    true,
                                    false,
                                    12,
                                    6),
                            60);
                    awaitOutcome(
                            helper,
                            bot,
                            completion,
                            () -> observedCrouching[0] |=
                                    bot.player().isShiftKeyDown()
                                            && bot.player().getPose()
                                                    == Pose.CROUCHING,
                            outcome -> {
                                requireState(
                                        outcome,
                                        ActionState.SUCCEEDED,
                                        ActionFailureCode.NONE);
                                require(
                                        observedCrouching[0],
                                        "Sneak input never produced the CROUCHING pose");
                                require(
                                        "true".equals(evidence(
                                                outcome,
                                                "move.observed_sneaking")),
                                        "Action evidence did not record crouching");
                                assertTerminalInputClear(bot.player());
                                assertNoTeleportAcknowledgement(bot);
                                removeAndSucceed(helper, bot);
                            });
                });
    }

    @GameTest(
            template = TEMPLATE,
            batch = "p2_movement_swim",
            timeoutTicks = TEST_TIMEOUT_TICKS)
    public static void shallowWaterSwimProducesForwardDisplacement(
            GameTestHelper helper) {
        prepareFloor(helper);
        for (int x = 3; x <= 5; x++) {
            for (int z = 2; z <= 7; z++) {
                helper.setBlock(new BlockPos(x, 1, z), Blocks.WATER);
                helper.setBlock(new BlockPos(x, 2, z), Blocks.WATER);
            }
        }

        TestBot bot = spawn(
                helper,
                "P2Swim",
                new Vec3(4.5D, 1.05D, 2.5D),
                0.0F);
        awaitCondition(
                helper,
                bot,
                30,
                () -> hasFreshPhysicsTick(bot)
                        && bot.player().isInWater(),
                "Bot did not enter the prepared water lane",
                () -> {
                    double startZ = bot.player().getZ();
                    boolean[] observedWaterSprint = {false};
                    CompletionStage<ActionOutcome> completion = submit(
                            bot,
                            new MoveInputAction(
                                    1.0F,
                                    0.0F,
                                    false,
                                    false,
                                    true,
                                    16,
                                    8),
                            80);
                    awaitOutcome(
                            helper,
                            bot,
                            completion,
                            () -> observedWaterSprint[0] |=
                                    bot.player().isInWater()
                                            && bot.player().isSprinting()
                                            && bot.player().zza > 0.0F,
                            outcome -> {
                                requireState(
                                        outcome,
                                        ActionState.SUCCEEDED,
                                        ActionFailureCode.NONE);
                                require(
                                        observedWaterSprint[0],
                                        "Swim intent never produced in-water sprint input");
                                require(
                                        bot.player().getZ()
                                                > startZ + 0.05D,
                                        "Swim intent produced no forward displacement");
                                require(
                                        "true".equals(evidence(
                                                outcome,
                                                "move.observed_water_sprint")),
                                        "Action evidence did not record water sprint");
                                assertTerminalInputClear(bot.player());
                                assertNoTeleportAcknowledgement(bot);
                                removeAndSucceed(helper, bot);
                            });
                });
    }

    @GameTest(
            template = TEMPLATE,
            batch = "p2_movement_wall",
            timeoutTicks = TEST_TIMEOUT_TICKS)
    public static void wallCollisionFailsWithoutPenetration(
            GameTestHelper helper) {
        prepareFloor(helper);
        for (int x = 1; x <= 7; x++) {
            for (int y = 1; y <= 3; y++) {
                helper.setBlock(new BlockPos(x, y, 5), Blocks.STONE);
            }
        }

        TestBot bot = spawn(
                helper,
                "P2Wall",
                new Vec3(4.5D, 1.0D, 3.8D),
                0.0F);
        awaitCondition(
                helper,
                bot,
                20,
                () -> hasFreshGroundContact(bot),
                "Bot did not settle on the test floor",
                () -> {
                    double wallFront =
                            helper.absolutePos(new BlockPos(4, 1, 5))
                                    .getZ();
                    CompletionStage<ActionOutcome> completion = submit(
                            bot,
                            new MoveInputAction(
                                    1.0F,
                                    0.0F,
                                    false,
                                    false,
                                    false,
                                    40,
                                    8),
                            100);
                    awaitOutcome(
                            helper,
                            bot,
                            completion,
                            () -> {},
                            outcome -> {
                                requireState(
                                        outcome,
                                        ActionState.FAILED,
                                        ActionFailureCode
                                                .PRECONDITION_FAILED);
                                require(
                                        "true".equals(evidence(
                                                outcome,
                                                "move.horizontal_collision")),
                                        "Collision failure did not include collision evidence");
                                require(
                                        bot.player()
                                                        .getBoundingBox()
                                                        .maxZ
                                                <= wallFront
                                                        + POSITION_EPSILON,
                                        "Bot penetrated the blocking wall");
                                assertTerminalInputClear(bot.player());
                                assertNoTeleportAcknowledgement(bot);
                                removeAndSucceed(helper, bot);
                            });
                });
    }

    @GameTest(
            template = TEMPLATE,
            batch = "p2_movement_airborne",
            timeoutTicks = TEST_TIMEOUT_TICKS)
    public static void airborneJumpIsRejected(
            GameTestHelper helper) {
        prepareFloor(helper);
        TestBot bot = spawn(
                helper,
                "P2Air",
                new Vec3(4.5D, 4.0D, 4.5D),
                0.0F);
        bot.player().setOnGround(false);
        bot.player().setDeltaMovement(Vec3.ZERO);

        CompletionStage<ActionOutcome> completion = submit(
                bot,
                new JumpAction(0.0F, 0.0F, false, 4),
                40);
        awaitOutcome(
                helper,
                bot,
                completion,
                () -> {},
                outcome -> {
                    requireState(
                            outcome,
                            ActionState.FAILED,
                            ActionFailureCode.PRECONDITION_FAILED);
                    assertTerminalInputClear(bot.player());
                    assertNoTeleportAcknowledgement(bot);
                    removeAndSucceed(helper, bot);
                });
    }

    private static void prepareFloor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 5; y++) {
                    helper.setBlock(
                            new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    private static TestBot spawn(
            GameTestHelper helper,
            String name,
            Vec3 relativePosition,
            float yaw) {
        ServerLevel level = helper.getLevel();
        Vec3 position = helper.absoluteVec(relativePosition);
        BotLifecycleManager manager =
                BotPlayerManagers.get(level.getServer());
        manager.removeByName(
                name,
                Component.literal(
                        "Resetting P2 movement GameTest"));

        try {
            BotServerPlayer player = manager.spawn(
                    level.getServer()
                            .createCommandSourceStack()
                            .withLevel(level)
                            .withPosition(position)
                            .withRotation(new Vec2(0.0F, yaw)),
                    name);
            resetPersistentTestState(player);
            resetTransientPhysicsState(player, yaw);
            player.teleportTo(
                    level,
                    position.x,
                    position.y,
                    position.z,
                    java.util.Set.of(),
                    yaw,
                    0.0F);
            /*
             * ServerPlayer teleportation does not promise to clear the collision and on-ground
             * flags loaded from player data. Clear them again so only a subsequent authoritative
             * physics Tick may establish contact at the new location.
             */
            resetTransientPhysicsState(player, yaw);
            player.xo = position.x;
            player.yo = position.y;
            player.zo = position.z;
            player.xOld = position.x;
            player.yOld = position.y;
            player.zOld = position.z;

            BotConnection connection = requireConnection(player);
            long teleportAcknowledgements =
                    connection.snapshot()
                            .teleportAcknowledgementCount();
            return new TestBot(
                    name,
                    manager,
                    player,
                    connection,
                    teleportAcknowledgements,
                    player.tickCount);
        } catch (RuntimeException | Error exception) {
            manager.removeByName(
                    name,
                    Component.literal(
                            "P2 movement GameTest setup failed"));
            throw exception;
        }
    }

    private static void resetPersistentTestState(
            BotServerPlayer player) {
        if (player.isSleeping()) {
            player.stopSleeping();
        }
        player.stopRiding();
        player.setGameMode(GameType.SURVIVAL);
        player.removeAllEffects();
        player.getInventory().clearContent();

        player.getAbilities().invulnerable = false;
        player.getAbilities().flying = false;
        player.getAbilities().instabuild = false;
        player.getAbilities().mayBuild = true;
        player.getAbilities().setFlyingSpeed(0.05F);
        player.getAbilities().setWalkingSpeed(0.1F);
        player.onUpdateAbilities();

        player.getFoodData().setFoodLevel(20);
        player.getFoodData().setSaturation(5.0F);
        player.getFoodData().setExhaustion(0.0F);
        player.setHealth(player.getMaxHealth());
        player.setRemainingFireTicks(0);
        player.setTicksFrozen(0);
        player.setAirSupply(player.getMaxAirSupply());
    }

    private static void resetTransientPhysicsState(
            BotServerPlayer player, float yaw) {
        player.noPhysics = false;
        player.setNoGravity(false);
        player.setDeltaMovement(Vec3.ZERO);
        player.resetFallDistance();
        player.setOnGround(false);
        player.horizontalCollision = false;
        player.verticalCollision = false;
        player.verticalCollisionBelow = false;
        player.minorHorizontalCollision = false;
        player.setPose(Pose.STANDING);
        player.setSwimming(false);
        player.stopFallFlying();
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        player.setJumping(false);
        player.stopUsingItem();
        player.setYRot(yaw);
        player.setXRot(0.0F);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
    }

    private static boolean hasFreshPhysicsTick(TestBot bot) {
        return bot.player().tickCount > bot.initializedPlayerTick();
    }

    private static boolean hasFreshGroundContact(TestBot bot) {
        return hasFreshPhysicsTick(bot)
                && bot.player().onGround()
                && bot.player().verticalCollisionBelow;
    }

    private static CompletionStage<ActionOutcome> submit(
            TestBot bot,
            ActionRequest action,
            int maxTicks) {
        long currentTick =
                bot.player().serverLevel().getServer().getTickCount();
        UUID actionId = UUID.randomUUID();
        ActionEnvelope envelope = new ActionEnvelope(
                actionId,
                bot.player().getUUID(),
                bot.player().runtimeHandle().generation(),
                "gametest/" + actionId,
                currentTick + maxTicks + 40L,
                maxTicks,
                action,
                ActionOrigin.none());
        ActionMailbox.Submission submission =
                bot.manager().submitAction(
                        envelope, ActionPriority.OWNER_TASK);
        if (submission.status()
                != ActionMailbox.SubmissionStatus.ENQUEUED) {
            removeQuietly(bot);
            throw new IllegalStateException(
                    "Action submission was rejected: "
                            + submission.status());
        }
        CompletionStage<ActionOutcome> completion =
                submission.completion().orElse(null);
        if (completion == null) {
            removeQuietly(bot);
            throw new IllegalStateException(
                    "Enqueued action did not expose a completion stage");
        }
        return completion;
    }

    private static void awaitCondition(
            GameTestHelper helper,
            TestBot bot,
            int remainingTicks,
            BooleanSupplier condition,
            String failureMessage,
            Runnable continuation) {
        boolean ready;
        try {
            ready = condition.getAsBoolean();
        } catch (RuntimeException | AssertionError exception) {
            failAndRemove(
                    helper, bot, exceptionMessage(exception));
            return;
        }
        if (ready) {
            try {
                continuation.run();
            } catch (RuntimeException | AssertionError exception) {
                failAndRemove(
                        helper, bot, exceptionMessage(exception));
            }
            return;
        }
        if (remainingTicks <= 0) {
            failAndRemove(helper, bot, failureMessage);
            return;
        }
        try {
            helper.runAfterDelay(
                    1L,
                    () -> awaitCondition(
                            helper,
                            bot,
                            remainingTicks - 1,
                            condition,
                            failureMessage,
                            continuation));
        } catch (RuntimeException | AssertionError exception) {
            failAndRemove(
                    helper, bot, exceptionMessage(exception));
        }
    }

    private static void awaitOutcome(
            GameTestHelper helper,
            TestBot bot,
            CompletionStage<ActionOutcome> completion,
            Runnable observation,
            Consumer<ActionOutcome> verifier) {
        awaitOutcome(
                helper,
                bot,
                completion,
                observation,
                verifier,
                OUTCOME_WAIT_TICKS);
    }

    private static void awaitOutcome(
            GameTestHelper helper,
            TestBot bot,
            CompletionStage<ActionOutcome> completion,
            Runnable observation,
            Consumer<ActionOutcome> verifier,
            int remainingTicks) {
        CompletableFuture<ActionOutcome> future =
                completion.toCompletableFuture();
        try {
            observation.run();
            if (!future.isDone()) {
                if (remainingTicks <= 0) {
                    failAndRemove(
                            helper,
                            bot,
                            "Action completion did not arrive within "
                                    + OUTCOME_WAIT_TICKS
                                    + " ticks");
                    return;
                }
                helper.runAfterDelay(
                        1L,
                        () -> awaitOutcome(
                                helper,
                                bot,
                                completion,
                                observation,
                                verifier,
                                remainingTicks - 1));
                return;
            }
            verifier.accept(future.join());
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            failAndRemove(
                    helper,
                    bot,
                    "Action completion failed: "
                            + (cause == null
                                    ? exception
                                    : cause));
        } catch (RuntimeException | AssertionError exception) {
            failAndRemove(
                    helper,
                    bot,
                    exceptionMessage(exception));
        }
    }

    private static void requireState(
            ActionOutcome outcome,
            ActionState expectedState,
            ActionFailureCode expectedFailureCode) {
        require(
                outcome.state() == expectedState,
                "Expected action state "
                        + expectedState
                        + " but got "
                        + outcome.state()
                        + ": "
                        + outcome.safeSummary());
        require(
                outcome.failureCode() == expectedFailureCode,
                "Expected failure code "
                        + expectedFailureCode
                        + " but got "
                        + outcome.failureCode());
    }

    private static String evidence(
            ActionOutcome outcome, String key) {
        return outcome.evidence().stream()
                .filter(item -> item.key().equals(key))
                .map(item -> item.value())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Missing action evidence: " + key));
    }

    private static void assertTerminalInputClear(
            BotServerPlayer player) {
        require(
                player.xxa == 0.0F
                        && player.yya == 0.0F
                        && player.zza == 0.0F
                        && !player.isShiftKeyDown()
                        && !player.isSprinting(),
                "Terminal movement input was not fully cleared");
    }

    private static void assertNoTeleportAcknowledgement(
            TestBot bot) {
        require(
                bot.connection()
                                .snapshot()
                                .teleportAcknowledgementCount()
                        == bot.teleportAcknowledgementsBeforeAction(),
                "Physical movement unexpectedly consumed a teleport acknowledgement");
    }

    private static BotConnection requireConnection(
            BotServerPlayer player) {
        if (player.connection != null
                && player.connection.getConnection()
                        instanceof BotConnection connection) {
            return connection;
        }
        throw new IllegalStateException(
                "BotPlayer does not have its virtual BotConnection");
    }

    private static void removeAndSucceed(
            GameTestHelper helper, TestBot bot) {
        require(
                bot.manager().removeByName(
                        bot.name(),
                        Component.literal(
                                "P2 movement GameTest completed")),
                "GameTest bot could not be removed");
        helper.succeed();
    }

    private static void failAndRemove(
            GameTestHelper helper,
            TestBot bot,
            String message) {
        removeQuietly(bot);
        helper.fail(message);
    }

    private static void removeQuietly(TestBot bot) {
        bot.manager().removeByName(
                bot.name(),
                Component.literal(
                        "P2 movement GameTest failed"));
    }

    private static String exceptionMessage(Throwable exception) {
        return exception.getMessage() == null
                ? exception.toString()
                : exception.getMessage();
    }

    private static void require(
            boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record TestBot(
            String name,
            BotLifecycleManager manager,
            BotServerPlayer player,
            BotConnection connection,
            long teleportAcknowledgementsBeforeAction,
            int initializedPlayerTick) {}
}
