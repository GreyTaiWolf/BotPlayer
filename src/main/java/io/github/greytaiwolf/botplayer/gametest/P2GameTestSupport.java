package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.action.ActionEnvelope;
import io.github.greytaiwolf.botplayer.action.ActionMailbox;
import io.github.greytaiwolf.botplayer.action.ActionOrigin;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionPriority;
import io.github.greytaiwolf.botplayer.action.ActionRequest;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.registration.NetworkRegistry;

final class P2GameTestSupport {
    static final String TEMPLATE = "empty_9x5x9";
    static final int TIMEOUT_TICKS = 240;

    private P2GameTestSupport() {}

    static void prepareEmptyFloor(GameTestHelper helper) {
        for (int x = 0; x < 9; x++) {
            for (int z = 0; z < 9; z++) {
                helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
                for (int y = 1; y < 5; y++) {
                    helper.setBlock(new BlockPos(x, y, z), Blocks.AIR);
                }
            }
        }
    }

    static TestBot spawnBot(
            GameTestHelper helper,
            ServerPlayer owner,
            String name,
            Vec3 relativePosition,
            float yaw) {
        return spawnBotWithName(
                helper,
                owner,
                uniqueBotName(name),
                relativePosition,
                yaw);
    }

    static TestBot spawnBotWithExactName(
            GameTestHelper helper,
            ServerPlayer owner,
            String name,
            Vec3 relativePosition,
            float yaw) {
        return spawnBotWithName(
                helper, owner, name, relativePosition, yaw);
    }

    private static TestBot spawnBotWithName(
            GameTestHelper helper,
            ServerPlayer owner,
            String name,
            Vec3 relativePosition,
            float yaw) {
        Objects.requireNonNull(helper, "helper");
        Objects.requireNonNull(name, "name");
        ServerLevel level = helper.getLevel();
        Vec3 position = helper.absoluteVec(relativePosition);
        BotLifecycleManager manager = BotPlayerManagers.get(level.getServer());
        BotServerPlayer player = manager.spawn(
                (owner == null
                                ? level.getServer().createCommandSourceStack()
                                : owner.createCommandSourceStack())
                        .withLevel(level)
                        .withPosition(position)
                        .withRotation(new Vec2(0.0F, yaw)),
                name);

        player.setGameMode(GameType.SURVIVAL);
        player.getInventory().clearContent();
        player.getAbilities().flying = false;
        player.onUpdateAbilities();
        player.setHealth(player.getMaxHealth());
        player.stopRiding();
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0.0F;
        player.setShiftKeyDown(false);
        player.setSprinting(false);
        player.setJumping(false);
        player.stopUsingItem();
        placePlayer(player, level, position, yaw);
        return new TestBot(name, manager, player);
    }

    @SuppressWarnings("removal")
    static ServerPlayer spawnViewer(
            GameTestHelper helper, Vec3 relativePosition) {
        ServerPlayer viewer = helper.makeMockServerPlayerInLevel();
        NetworkRegistry.configureMockConnection(
                viewer.connection.getConnection());
        Vec3 position = helper.absoluteVec(relativePosition);
        viewer.setGameMode(GameType.SURVIVAL);
        viewer.getInventory().clearContent();
        placePlayer(viewer, helper.getLevel(), position, 0.0F);
        return viewer;
    }

    static void placePlayer(
            ServerPlayer player,
            ServerLevel level,
            Vec3 absolutePosition,
            float yaw) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(
                absolutePosition, "absolutePosition");
        require(
                player.serverLevel() == level,
                "GameTest player is not in the expected level");

        /*
         * A vanilla mock connection has no client to acknowledge a teleport. Set the
         * authoritative entity and connection baselines together, matching NeoForge's
         * GameTestPlayer positioning, so a later listener tick cannot restore the login
         * coordinates.
         */
        player.stopRiding();
        player.moveTo(
                absolutePosition.x,
                absolutePosition.y,
                absolutePosition.z);
        player.setYRot(yaw);
        player.setXRot(0.0F);
        player.setYHeadRot(yaw);
        player.setYBodyRot(yaw);
        level.getChunkSource().move(player);
        player.connection.resetPosition();
    }

    static CompletionStage<ActionOutcome> submit(
            TestBot bot, ActionRequest action, int maxTicks) {
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
        ActionMailbox.Submission submission = bot.manager().submitAction(
                envelope, ActionPriority.OWNER_TASK);
        require(
                submission.status()
                        == ActionMailbox.SubmissionStatus.ENQUEUED,
                "Action submission was rejected: " + submission.status());
        return submission.completion().orElseThrow();
    }

    static void awaitCondition(
            GameTestHelper helper,
            int remainingTicks,
            BooleanSupplier condition,
            String failureMessage,
            Runnable failureCleanup,
            Runnable continuation) {
        Objects.requireNonNull(failureMessage, "failureMessage");
        awaitCondition(
                helper,
                remainingTicks,
                condition,
                () -> failureMessage,
                failureCleanup,
                continuation);
    }

    static void awaitCondition(
            GameTestHelper helper,
            int remainingTicks,
            BooleanSupplier condition,
            Supplier<String> failureMessageSupplier,
            Runnable failureCleanup,
            Runnable continuation) {
        Objects.requireNonNull(failureMessageSupplier,
                "failureMessageSupplier");
        try {
            if (condition.getAsBoolean()) {
                continuation.run();
                return;
            }
        } catch (RuntimeException | AssertionError exception) {
            failureCleanup.run();
            helper.fail(
                    exception.getMessage() == null
                            ? exception.toString()
                            : exception.getMessage());
            return;
        }
        if (remainingTicks <= 0) {
            failureCleanup.run();
            helper.fail(Objects.requireNonNull(
                    failureMessageSupplier.get(), "failureMessage"));
            return;
        }
        helper.runAfterDelay(
                1L,
                () -> awaitCondition(
                        helper,
                        remainingTicks - 1,
                        condition,
                        failureMessageSupplier,
                        failureCleanup,
                        continuation));
    }

    static void awaitOutcome(
            GameTestHelper helper,
            CompletionStage<ActionOutcome> completion,
            int remainingTicks,
            Runnable failureCleanup,
            Consumer<ActionOutcome> verifier) {
        CompletableFuture<ActionOutcome> future =
                completion.toCompletableFuture();
        if (!future.isDone()) {
            if (remainingTicks <= 0) {
                failureCleanup.run();
                helper.fail(
                        "Action did not complete within the GameTest bound");
                return;
            }
            helper.runAfterDelay(
                    1L,
                    () -> awaitOutcome(
                            helper,
                            completion,
                            remainingTicks - 1,
                            failureCleanup,
                            verifier));
            return;
        }

        try {
            verifier.accept(future.join());
        } catch (CompletionException exception) {
            failureCleanup.run();
            Throwable cause = exception.getCause();
            helper.fail(
                    "Action completion failed: "
                            + (cause == null ? exception : cause));
        } catch (RuntimeException | AssertionError exception) {
            failureCleanup.run();
            helper.fail(
                    exception.getMessage() == null
                            ? exception.toString()
                            : exception.getMessage());
        }
    }

    static void removeBot(TestBot bot, String reason) {
        bot.manager().removeByName(bot.name(), Component.literal(reason));
    }

    static void disconnectViewer(ServerPlayer viewer) {
        if (viewer != null
                && viewer.connection != null
                && viewer.getServer() != null
                && viewer.getServer()
                                .getPlayerList()
                                .getPlayer(viewer.getUUID())
                        == viewer) {
            viewer.connection.disconnect(
                    Component.literal("P2 GameTest completed"));
        }
    }

    static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String uniqueBotName(String requestedName) {
        Objects.requireNonNull(requestedName, "requestedName");
        String suffix = UUID.randomUUID().toString().substring(0, 6);
        int prefixLength = 16 - suffix.length() - 1;
        String prefix = requestedName.substring(
                0, Math.min(requestedName.length(), prefixLength));
        return prefix + "_" + suffix;
    }

    record TestBot(
            String name,
            BotLifecycleManager manager,
            BotServerPlayer player) {}

    static final class Cleanup implements Runnable {
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final java.util.ArrayDeque<Runnable> actions =
                new java.util.ArrayDeque<>();

        void add(Runnable action) {
            require(
                    !cleaned.get(),
                    "Cannot add cleanup after it has run");
            actions.addFirst(
                    Objects.requireNonNull(action, "action"));
        }

        @Override
        public void run() {
            if (!cleaned.compareAndSet(false, true)) {
                return;
            }
            RuntimeException firstFailure = null;
            for (Runnable action : actions) {
                try {
                    action.run();
                } catch (RuntimeException exception) {
                    if (firstFailure == null) {
                        firstFailure = exception;
                    } else {
                        firstFailure.addSuppressed(exception);
                    }
                }
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        }
    }
}
