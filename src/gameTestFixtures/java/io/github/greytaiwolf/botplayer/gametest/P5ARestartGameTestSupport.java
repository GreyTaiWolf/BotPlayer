package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.interaction.menu.PlayerInventoryMenuLayout;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import io.github.greytaiwolf.botplayer.lifecycle.BotLifecycleManager;
import io.github.greytaiwolf.botplayer.lifecycle.BotPlayerManagers;
import io.github.greytaiwolf.botplayer.persistence.BotRosterSavedData;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpoint;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointNodeState;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointSavedData;
import io.github.greytaiwolf.botplayer.skill.checkpoint.SkillCheckpointScope;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Test-only coordination for the P5A two-start recovery lane.
 *
 * <p>The phase-one server must stop at the very ServerTick.Post that has just
 * written a safe checkpoint. A later GameTest callback can be one tick too
 * late, allowing the next node to dispatch and correctly fence that checkpoint.
 * This fixture therefore observes only its explicitly armed identity at LOWEST
 * priority, after the production lifecycle has persisted its safe point.
 */
@EventBusSubscriber(modid = BotPlayer.MOD_ID)
public final class P5ARestartGameTestSupport {
    static final String PHASE_ONE_NAMESPACE =
            "botplayer_p5a_restart_one";
    static final String PHASE_TWO_NAMESPACE =
            "botplayer_p5a_restart_two";
    static final String IDENTITY_NAMESPACE = "r5tart1";
    static final String ROLE = "boot";
    static final String BOT_NAME = "p5_r5tart1_boot";
    static final int PERSISTED_ITEM_SLOT = 35;
    static final Vec3 SOURCE_POSITION =
            new Vec3(4.5D, 1.0D, 4.5D);

    private static final int COMPLETED_LOG_FRAGMENTS = 4;
    /* Mirrors the bounded radius observed by MinecraftSkillCheckpointScopeObserver. */
    private static final int SCOPE_CHUNK_RADIUS = 8;
    private static final AtomicReference<PhaseOneArm> PHASE_ONE_ARM =
            new AtomicReference<>();

    private P5ARestartGameTestSupport() {}

    static Optional<SkillCheckpoint> checkpointFor(
            MinecraftServer server, UUID botId) {
        BotRosterSavedData roster = BotRosterSavedData.get(server);
        return SkillCheckpointSavedData.get(
                server, roster.serverInstanceId()).load(botId);
    }

    static void armPhaseOneShutdown(
            MinecraftServer server,
            BotLifecycleManager manager,
            UUID botId) {
        PhaseOneArm arm = new PhaseOneArm(server, manager, botId);
        P2GameTestSupport.require(
                PHASE_ONE_ARM.compareAndSet(null, arm),
                "P5A restart phase one was armed more than once");
    }

    static Optional<SkillCheckpoint> phaseOneCheckpoint(
            MinecraftServer server) {
        PhaseOneArm arm = phaseOneArm(server);
        return arm == null ? Optional.empty() : arm.checkpoint();
    }

    /** Returns the armed phase-one view solely for timeout diagnostics. */
    static Optional<SkillRunView> phaseOneRun(MinecraftServer server) {
        PhaseOneArm arm = phaseOneArm(server);
        return arm == null
                ? Optional.empty()
                : arm.manager().skillRun(arm.botId());
    }

    static Optional<String> phaseOneFailure(MinecraftServer server) {
        PhaseOneArm arm = phaseOneArm(server);
        return arm == null ? Optional.empty() : arm.failure();
    }

    static boolean phaseOneShutdownIssued(MinecraftServer server) {
        PhaseOneArm arm = phaseOneArm(server);
        return arm != null && arm.shutdownIssued();
    }

    static void clearPhaseOneShutdown(MinecraftServer server) {
        PhaseOneArm arm = phaseOneArm(server);
        if (arm != null) {
            PHASE_ONE_ARM.compareAndSet(arm, null);
        }
    }

    /**
     * Reattaches the durable identity without the ordinary GameTest spawn
     * helper's inventory clear or teleport. Those mutations would make the
     * second process a false recovery test.
     */
    static RestoredBot spawnRestored(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        MinecraftServer server = level.getServer();
        BotLifecycleManager manager = BotPlayerManagers.get(server);
        BotServerPlayer player = manager.spawn(
                server.createCommandSourceStack()
                        .withLevel(level)
                        .withPosition(helper.absoluteVec(SOURCE_POSITION))
                        .withRotation(new Vec2(0.0F, 0.0F)),
                BOT_NAME);
        return new RestoredBot(manager, player);
    }

    static void removeRestored(RestoredBot bot, String reason) {
        bot.manager().removeByName(BOT_NAME, Component.literal(reason));
    }

    /**
     * GameTestServer chooses a fresh, random structure position per process.
     * Loading this small existing area is not a world mutation; it makes the
     * checkpoint observer's bounded loaded-chunk precondition deterministic
     * for both the original body and the body restored at its prior position.
     */
    static void loadCheckpointScopeChunks(BotServerPlayer player) {
        ServerLevel level = player.serverLevel();
        BlockPos anchor = BlockPos.containing(
                player.getX(), player.getY(), player.getZ());
        int minimumChunkX = Math.floorDiv(
                anchor.getX() - SCOPE_CHUNK_RADIUS, 16);
        int maximumChunkX = Math.floorDiv(
                anchor.getX() + SCOPE_CHUNK_RADIUS, 16);
        int minimumChunkZ = Math.floorDiv(
                anchor.getZ() - SCOPE_CHUNK_RADIUS, 16);
        int maximumChunkZ = Math.floorDiv(
                anchor.getZ() + SCOPE_CHUNK_RADIUS, 16);
        for (int chunkX = minimumChunkX;
                chunkX <= maximumChunkX;
                chunkX++) {
            for (int chunkZ = minimumChunkZ;
                    chunkZ <= maximumChunkZ;
                    chunkZ++) {
                level.getChunk(chunkX, chunkZ);
            }
        }
    }

    static int count(BotServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    static void requireRestoredBodyMatchesScope(
            BotServerPlayer player, SkillCheckpointScope scope) {
        P2GameTestSupport.require(
                player.serverLevel().dimension().location().toString().equals(
                        scope.dimensionId()),
                "Restored Bot entered a different dimension than its checkpoint scope");
        BlockPos anchor = BlockPos.containing(
                player.getX(), player.getY(), player.getZ());
        P2GameTestSupport.require(
                anchor.getX() == scope.blockX()
                        && anchor.getY() == scope.blockY()
                        && anchor.getZ() == scope.blockZ(),
                "Restored Bot did not retain the checkpoint anchor");
        P2GameTestSupport.require(
                player.containerMenu == player.inventoryMenu
                        && player.inventoryMenu.getCarried().isEmpty(),
                "Restored Bot has a non-native inventory menu or cursor");
        P2GameTestSupport.require(
                player.inventoryMenu.slots.size()
                        == PlayerInventoryMenuLayout.LAST_MENU_SLOT + 1,
                "Restored Bot inventory menu has an unexpected slot layout");
    }

    static boolean isRestartableSafePoint(
            SkillCheckpoint checkpoint,
            SkillRunView view,
            UUID botId,
            long generation) {
        return view.state() == SkillRunState.PREPARING
                && view.completedNodes() >= COMPLETED_LOG_FRAGMENTS
                && !checkpoint.continuationState().isTerminal()
                && checkpoint.scope().isPresent()
                && checkpoint.botId().equals(botId)
                && checkpoint.playerId().equals(botId)
                && checkpoint.generation() == generation
                && checkpoint.runId().equals(view.runId())
                && checkpoint.plan().planId().equals(view.planId())
                && checkpoint.plan().revision() == view.planRevision()
                && checkpoint.stateRevision() == view.stateRevision()
                && checkpoint.nodes().stream().filter(node -> node.state()
                        == SkillCheckpointNodeState.SUCCEEDED).count()
                        >= COMPLETED_LOG_FRAGMENTS;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void stopPhaseOneAtSafeCheckpoint(ServerTickEvent.Post event) {
        PhaseOneArm arm = phaseOneArm(event.getServer());
        if (arm == null || arm.shutdownIssued()) {
            return;
        }
        try {
            SkillRunView view = arm.manager().skillRun(arm.botId())
                    .orElse(null);
            SkillCheckpoint checkpoint = checkpointFor(
                    event.getServer(), arm.botId()).orElse(null);
            if (view == null || checkpoint == null
                    || !isRestartableSafePoint(
                            checkpoint,
                            view,
                            arm.botId(),
                            arm.generation())) {
                return;
            }
            arm.recordCheckpoint(checkpoint);
            if (arm.markShutdownIssued()) {
                arm.manager().shutdown();
            }
        } catch (RuntimeException | AssertionError exception) {
            arm.recordFailure(exception);
        }
    }

    private static PhaseOneArm phaseOneArm(MinecraftServer server) {
        PhaseOneArm arm = PHASE_ONE_ARM.get();
        return arm != null && arm.server() == server ? arm : null;
    }

    record RestoredBot(BotLifecycleManager manager, BotServerPlayer player) {}

    private static final class PhaseOneArm {
        private final MinecraftServer server;
        private final BotLifecycleManager manager;
        private final UUID botId;
        private final long generation;
        private final AtomicReference<SkillCheckpoint> checkpoint =
                new AtomicReference<>();
        private final AtomicReference<String> failure = new AtomicReference<>();
        private final AtomicBoolean shutdownIssued = new AtomicBoolean();

        private PhaseOneArm(
                MinecraftServer server,
                BotLifecycleManager manager,
                UUID botId) {
            this.server = java.util.Objects.requireNonNull(server, "server");
            this.manager = java.util.Objects.requireNonNull(manager, "manager");
            this.botId = java.util.Objects.requireNonNull(botId, "botId");
            this.generation = manager.skillRun(botId)
                    .map(SkillRunView::botGeneration)
                    .orElseThrow(() -> new IllegalStateException(
                            "P5A restart phase one was armed before submission"));
        }

        private MinecraftServer server() {
            return server;
        }

        private BotLifecycleManager manager() {
            return manager;
        }

        private UUID botId() {
            return botId;
        }

        private long generation() {
            return generation;
        }

        private Optional<SkillCheckpoint> checkpoint() {
            return Optional.ofNullable(checkpoint.get());
        }

        private Optional<String> failure() {
            return Optional.ofNullable(failure.get());
        }

        private boolean shutdownIssued() {
            return shutdownIssued.get();
        }

        private void recordCheckpoint(SkillCheckpoint value) {
            checkpoint.compareAndSet(null, value);
        }

        private boolean markShutdownIssued() {
            return shutdownIssued.compareAndSet(false, true);
        }

        private void recordFailure(Throwable exception) {
            String message = exception.getMessage();
            failure.compareAndSet(
                    null,
                    message == null ? exception.toString() : message);
        }
    }
}
