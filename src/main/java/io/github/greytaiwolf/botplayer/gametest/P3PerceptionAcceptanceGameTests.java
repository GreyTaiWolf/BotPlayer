package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.perception.BudgetKind;
import io.github.greytaiwolf.botplayer.perception.GlobalPerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.ObservationSnapshot;
import io.github.greytaiwolf.botplayer.perception.PerceptionBudget;
import io.github.greytaiwolf.botplayer.perception.SensorSchedule;
import io.github.greytaiwolf.botplayer.perception.VisionObservation;
import io.github.greytaiwolf.botplayer.perception.event.AuthorityEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import io.github.greytaiwolf.botplayer.perception.sensor.LocalBlockSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorContext;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorResult;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorSupport;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P3 有限感知、认知隔离与 generation 生命周期的游戏内验收。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P3PerceptionAcceptanceGameTests {
    private static final String BATCH = "p3_perception";
    private static final int SNAPSHOT_WAIT_TICKS = 100;

    private P3PerceptionAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void snapshotContainsAuthoritativeSelfAndFortyOneSlots(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P3Self",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanupBot(bot);
        try {
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.COMPASS));
            bot.player()
                    .getInventory()
                    .setItem(40, new ItemStack(Items.SHIELD));

            awaitSnapshot(
                    helper,
                    bot,
                    snapshot -> snapshot.inventory().totalSlots() == 41
                            && !snapshot.inventory().truncated(),
                    "P3 snapshot did not expose a complete 41-slot inventory",
                    cleanup,
                    snapshot -> {
                        P2GameTestSupport.require(
                                snapshot.botId()
                                        .equals(bot.player().getUUID()),
                                "Snapshot bot UUID differed from the authoritative body");
                        P2GameTestSupport.require(
                                snapshot.botGeneration()
                                        == bot.player()
                                                .runtimeHandle()
                                                .generation(),
                                "Snapshot generation differed from the active runtime");
                        P2GameTestSupport.require(
                                snapshot.self()
                                                .position()
                                                .distanceSquared(
                                                        new SpatialPoint(
                                                                bot.player()
                                                                        .getX(),
                                                                bot.player()
                                                                        .getY(),
                                                                bot.player()
                                                                        .getZ()))
                                        < 0.25D,
                                "Self position was not refreshed from the authoritative body");
                        P2GameTestSupport.require(
                                snapshot.inventory().occupiedSlots() == 2,
                                "Inventory snapshot did not preserve the two occupied slots");
                        P2GameTestSupport.require(
                                hasItem(snapshot, 0, "minecraft:compass")
                                        && hasItem(
                                                snapshot,
                                                40,
                                                "minecraft:shield"),
                                "Inventory snapshot did not preserve stable slot identities");
                        P2GameTestSupport.require(
                                !snapshot.limits().forcedChunkLoads(),
                                "Self/inventory sampling reported a forced chunk load");
                        cleanup.run();
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void gazeRaySeesEntityThenStopsAtOccludingWall(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    null,
                    "P3Vision",
                    new Vec3(4.5D, 1.0D, 2.0D),
                    0.0F);
            cleanup.add(() -> P2GameTestSupport.removeBot(
                    bot, "P3 vision GameTest completed"));
            ServerPlayer target = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.5D, 1.0D, 6.0D));
            cleanup.add(() ->
                    P2GameTestSupport.disconnectViewer(target));

            awaitSnapshot(
                    helper,
                    bot,
                    snapshot -> snapshot.vision().kind()
                                    == VisionObservation.Kind.ENTITY
                            && snapshot.vision()
                                    .entityId()
                                    .filter(target.getUUID()::equals)
                                    .isPresent(),
                    "P3 gaze ray did not resolve the visible target entity",
                    cleanup,
                    visible -> {
                        P2GameTestSupport.require(
                                visible.vision().lineOfSight(),
                                "Visible entity observation lacked line-of-sight evidence");
                        BlockPos lowerWall =
                                new BlockPos(4, 1, 4);
                        BlockPos upperWall =
                                new BlockPos(4, 2, 4);
                        helper.setBlock(lowerWall, Blocks.STONE);
                        helper.setBlock(upperWall, Blocks.STONE);
                        long wallTick = helper.getLevel()
                                .getServer()
                                .getTickCount();

                        awaitSnapshot(
                                helper,
                                bot,
                                snapshot -> snapshot.gameTick() > wallTick
                                        && snapshot.vision().kind()
                                                == VisionObservation.Kind.BLOCK
                                        && snapshot.vision()
                                                .objectId()
                                                .filter(
                                                        "minecraft:stone"::equals)
                                                .isPresent(),
                                "P3 gaze ray passed through an occluding stone wall",
                                cleanup,
                                occluded -> {
                                    P2GameTestSupport.require(
                                            occluded.vision()
                                                    .entityId()
                                                    .isEmpty(),
                                            "Occluded vision retained the hidden entity UUID");
                                    P2GameTestSupport.require(
                                            !occluded.limits()
                                                    .forcedChunkLoads(),
                                            "Vision sampling reported a forced chunk load");
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void remoteAuthorityEventDoesNotBecomeBotKnowledge(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P3Authority",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanupBot(bot);
        try {
            awaitSnapshot(
                    helper,
                    bot,
                    snapshot -> true,
                    "P3 did not produce the baseline perception snapshot",
                    cleanup,
                    baseline -> {
                        ItemEntity remote = new ItemEntity(
                                helper.getLevel(),
                                bot.player().getX() + 96.0D,
                                bot.player().getY(),
                                bot.player().getZ(),
                                new ItemStack(Items.COBBLESTONE));
                        AuthorityEvent authority = bot.manager()
                                .authorityEventCollector()
                                .recordDamage(
                                        remote,
                                        null,
                                        1.0F,
                                        helper.getLevel()
                                                .getServer()
                                                .getTickCount())
                                .orElseThrow(() -> new AssertionError(
                                        "Remote authority event was rejected by ingress limits"));

                        awaitSnapshot(
                                helper,
                                bot,
                                snapshot -> snapshot.snapshotId()
                                        >= baseline.snapshotId() + 3L,
                                "P3 did not produce snapshots after the remote authority event",
                                cleanup,
                                observed -> {
                                    P2GameTestSupport.require(
                                            observed.recentEvents()
                                                    .stream()
                                                    .noneMatch(event ->
                                                            event.authorityEventId()
                                                                    .equals(authority
                                                                            .event()
                                                                            .eventId())),
                                            "Remote authority leaked into the bot cognitive stream");
                                    P2GameTestSupport.require(
                                            observed.recentEvents()
                                                    .stream()
                                                    .flatMap(event -> event
                                                            .actors()
                                                            .stream())
                                                    .noneMatch(actor -> actor
                                                            .actorId()
                                                            .equals(remote
                                                                    .getUUID())),
                                            "Remote actor UUID leaked into the bot cognitive stream");
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void generationRotationStartsANewCognitiveStream(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P3Generation",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanupBot(bot);
        try {
            bot.manager().correctPerceivedActivity(
                    helper.getLevel()
                            .getServer()
                            .createCommandSourceStack()
                            .withPermission(4),
                    bot.name(), bot.name(), "moving");
            awaitSnapshot(
                    helper,
                    bot,
                    snapshot -> snapshot.recentEvents()
                            .stream()
                            .anyMatch(event -> event.type()
                                    == SemanticEventType.PLAYER_CORRECTION),
                    "P3 correction did not enter the active cognitive stream",
                    cleanup,
                    beforeRotation -> {
                        UUID oldCorrectionId = beforeRotation
                                .recentEvents()
                                .stream()
                                .filter(event -> event.type()
                                        == SemanticEventType.PLAYER_CORRECTION)
                                .findFirst()
                                .orElseThrow()
                                .authorityEventId();
                        UUID oldStreamId = beforeRotation.streamId();
                        long oldGeneration =
                                beforeRotation.botGeneration();
                        BlockPos pendingBreak = bot.player()
                                .blockPosition()
                                .offset(1, 0, 0);
                        helper.getLevel().setBlockAndUpdate(
                                pendingBreak,
                                Blocks.STONE.defaultBlockState());
                        bot.manager()
                                .authorityEventCollector()
                                .recordBlockBreak(
                                        helper.getLevel(),
                                        pendingBreak,
                                        helper.getLevel()
                                                .getBlockState(pendingBreak),
                                        bot.player());
                        helper.getLevel().setBlockAndUpdate(
                                pendingBreak,
                                Blocks.AIR.defaultBlockState());

                        bot.manager().onChangedDimension(bot.player());
                        long newGeneration = bot.player()
                                .runtimeHandle()
                                .generation();
                        P2GameTestSupport.require(
                                newGeneration > oldGeneration,
                                "Dimension lifecycle did not rotate the generation");
                        P2GameTestSupport.require(
                                bot.manager()
                                        .latestPerception(bot.name())
                                        .isEmpty(),
                                "Old generation snapshot survived rotation");

                        awaitSnapshot(
                                helper,
                                bot,
                                snapshot -> snapshot.botGeneration()
                                        == newGeneration,
                                "P3 did not create a snapshot for the rotated generation",
                                cleanup,
                                afterRotation -> {
                                    P2GameTestSupport.require(
                                            !afterRotation.streamId()
                                                    .equals(oldStreamId),
                                            "Rotated generation reused the old cognitive stream");
                                    P2GameTestSupport.require(
                                            afterRotation.recentEvents()
                                                    .stream()
                                                    .noneMatch(event ->
                                                            event.authorityEventId()
                                                                    .equals(oldCorrectionId)),
                                            "Rotated generation retained old perceived events");
                                    P2GameTestSupport.require(
                                            afterRotation.recentEvents()
                                                    .stream()
                                                    .noneMatch(event ->
                                                            event.type()
                                                                            == SemanticEventType
                                                                                    .BLOCK_BROKEN
                                                                    && event.channel()
                                                                            == PerceptionChannel
                                                                                    .SELF),
                                            "Pending old-generation mutation entered the new SELF stream");
                                    P2GameTestSupport.require(
                                            afterRotation.recentEvents()
                                                    .stream()
                                                    .filter(event ->
                                                            event.type()
                                                                    == SemanticEventType
                                                                            .BLOCK_BROKEN)
                                                    .flatMap(event -> event
                                                            .actors()
                                                            .stream())
                                                    .noneMatch(actor -> actor
                                                            .actorId()
                                                            .equals(bot
                                                                    .player()
                                                                    .getUUID())),
                                            "Pending old-generation mutation was attributed to the new body");
                                    cleanup.run();
                                    helper.succeed();
                                });
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void outOfRangeBlockFocusDoesNotLoadItsChunk(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P3NoLoad",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanupBot(bot);
        try {
            BlockPos farFocus = bot.player()
                    .blockPosition()
                    .offset(4096, 0, 0);
            P2GameTestSupport.require(
                    !helper.getLevel().isLoaded(farFocus),
                    "Far focus chunk unexpectedly started loaded");
            Vec3 unloadedStart = Vec3.atCenterOf(farFocus);
            Vec3 unloadedEnd =
                    unloadedStart.add(15.0D, 0.0D, 0.0D);
            SensorSupport.LoadedRay guardedRay =
                    SensorSupport.loadedRay(
                            helper.getLevel(),
                            unloadedStart,
                            unloadedEnd);
            P2GameTestSupport.require(
                    guardedRay.unloadedPoint().isPresent()
                            && guardedRay.loadedEnd()
                                    .distanceToSqr(unloadedStart)
                                    <= 1.0E-12D,
                    "Loaded-ray guard did not stop at the unloaded start chunk");
            P2GameTestSupport.require(
                    !helper.getLevel().isLoaded(farFocus)
                            && !helper.getLevel().isLoaded(
                                    BlockPos.containing(unloadedEnd)),
                    "Loaded-ray guard force-loaded an in-range chunk");

            EnumMap<BudgetKind, Integer> limits =
                    new EnumMap<>(BudgetKind.class);
            for (BudgetKind kind : BudgetKind.values()) {
                limits.put(kind, 64);
            }
            PerceptionBudget budget = new PerceptionBudget(
                    new GlobalPerceptionBudget(512), limits);
            LocalBlockSensor sensor = new LocalBlockSensor(
                    0,
                    1,
                    8,
                    16.0D,
                    new SensorSchedule(1, 1, 1));
            SensorContext context = new SensorContext(
                    bot.player(),
                    helper.getLevel().getServer().getTickCount(),
                    Optional.empty(),
                    List.of(farFocus),
                    List.of());
            SensorResult result = sensor.sample(context, budget);

            P2GameTestSupport.require(
                    result instanceof SensorResult.Blocks blocks
                            && blocks.truncated(),
                    "Out-of-range block focus was not rejected as truncated");
            P2GameTestSupport.require(
                    !helper.getLevel().isLoaded(farFocus),
                    "Block focus sampling force-loaded a distant chunk");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void uncommittedMutationCandidatesDoNotBecomeAuthority(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P3PostState",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanupBot(bot);
        try {
            BlockPos unchangedBreak =
                    helper.absolutePos(new BlockPos(2, 0, 2));
            BlockPos cancelledPlace =
                    helper.absolutePos(new BlockPos(2, 1, 2));
            ItemEntity unspawnedToss = new ItemEntity(
                    helper.getLevel(),
                    bot.player().getX(),
                    bot.player().getY(),
                    bot.player().getZ(),
                    new ItemStack(Items.COBBLESTONE));
            long beforeSequence = bot.manager()
                    .perceptionAuthoritySequence();
            long tick = helper.getLevel()
                    .getServer()
                    .getTickCount();

            bot.manager().authorityEventCollector().recordBlockBreak(
                    helper.getLevel(),
                    unchangedBreak,
                    helper.getLevel().getBlockState(unchangedBreak),
                    bot.player());
            bot.manager().authorityEventCollector().recordBlockPlace(
                    helper.getLevel(),
                    cancelledPlace,
                    helper.getLevel().getBlockState(cancelledPlace),
                    Blocks.DIRT.defaultBlockState(),
                    bot.player());
            bot.manager().authorityEventCollector().recordToss(
                    bot.player(), unspawnedToss, tick);
            bot.manager().authorityEventCollector().flush(tick);

            P2GameTestSupport.require(
                    bot.manager()
                                    .perceptionAuthoritySequence()
                            == beforeSequence,
                    "Uncommitted break/place/toss candidates entered authority");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static boolean hasItem(
            ObservationSnapshot snapshot, int slot, String itemId) {
        return snapshot.inventory()
                .items()
                .stream()
                .anyMatch(item -> item.slot() == slot
                        && item.itemId().equals(itemId));
    }

    private static P2GameTestSupport.Cleanup cleanupBot(TestBot bot) {
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P3 perception GameTest completed"));
        return cleanup;
    }

    private static void awaitSnapshot(
            GameTestHelper helper,
            TestBot bot,
            Predicate<ObservationSnapshot> predicate,
            String failureMessage,
            Runnable cleanup,
            Consumer<ObservationSnapshot> continuation) {
        P2GameTestSupport.awaitCondition(
                helper,
                SNAPSHOT_WAIT_TICKS,
                () -> bot.manager()
                        .latestPerception(bot.name())
                        .filter(predicate)
                        .isPresent(),
                failureMessage,
                cleanup,
                () -> continuation.accept(bot.manager()
                        .latestPerception(bot.name())
                        .filter(predicate)
                        .orElseThrow()));
    }
}
