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
import io.github.greytaiwolf.botplayer.perception.event.PerceivedEvent;
import io.github.greytaiwolf.botplayer.perception.event.PerceptionChannel;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventOutcome;
import io.github.greytaiwolf.botplayer.perception.event.SemanticEventType;
import io.github.greytaiwolf.botplayer.perception.event.SpatialPoint;
import io.github.greytaiwolf.botplayer.perception.sensor.LocalBlockSensor;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorContext;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorResult;
import io.github.greytaiwolf.botplayer.perception.sensor.SensorSupport;
import io.github.greytaiwolf.botplayer.worldmodel.FactStatus;
import io.github.greytaiwolf.botplayer.worldmodel.WorldFact;
import java.util.EnumMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
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
    private static final String DIRECTED_SOUND_BATCH =
            "p3_perception_directed_sound";
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
            bot.player().setHealth(13.0F);
            bot.player().getFoodData().setFoodLevel(7);
            bot.player().getFoodData().setSaturation(2.5F);

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
                                Float.compare(
                                                snapshot.self().health(),
                                                bot.player().getHealth())
                                        == 0,
                                "Self health differed from the authoritative body");
                        P2GameTestSupport.require(
                                snapshot.self().food()
                                        == bot.player()
                                                .getFoodData()
                                                .getFoodLevel(),
                                "Self food differed from the authoritative body");
                        P2GameTestSupport.require(
                                Float.compare(
                                                snapshot.self().saturation(),
                                                bot.player()
                                                        .getFoodData()
                                                        .getSaturationLevel())
                                        == 0,
                                "Self saturation differed from the authoritative body");
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
            batch = DIRECTED_SOUND_BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void directedSoundStaysWithinTargetBotGeneration(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            TestBot target = P2GameTestSupport.spawnBot(
                    helper,
                    null,
                    "P3SoundTarget",
                    new Vec3(3.5D, 1.0D, 4.5D),
                    0.0F);
            cleanup.add(() -> P2GameTestSupport.removeBot(
                    target, "P3 directed sound GameTest completed"));
            TestBot other = P2GameTestSupport.spawnBot(
                    helper,
                    null,
                    "P3SoundOther",
                    new Vec3(6.5D, 1.0D, 4.5D),
                    0.0F);
            cleanup.add(() -> P2GameTestSupport.removeBot(
                    other, "P3 directed sound GameTest completed"));

            awaitSnapshot(
                    helper,
                    target,
                    snapshot -> true,
                    "P3 target bot did not produce a baseline snapshot",
                    cleanup,
                    targetBaseline -> awaitSnapshot(
                            helper,
                            other,
                            snapshot -> true,
                            "P3 non-target bot did not produce a baseline snapshot",
                            cleanup,
                            otherBaseline -> {
                                String soundId =
                                        "minecraft:ambient.cave";
                                long offeredAtTick = helper.getLevel()
                                        .getServer()
                                        .getTickCount();
                                // 真实走目标 bot 的虚拟 listener，非目标 listener 不接收该包。
                                target.player().connection.send(
                                        new ClientboundSoundPacket(
                                                SoundEvents.AMBIENT_CAVE,
                                                SoundSource.MASTER,
                                                target.player().getX(),
                                                target.player().getY(),
                                                target.player().getZ(),
                                                1.0F,
                                                1.0F,
                                                42L));

                                P2GameTestSupport.awaitCondition(
                                        helper,
                                        SNAPSHOT_WAIT_TICKS,
                                        () -> target.manager()
                                                        .latestPerception(
                                                                target.name())
                                                        .filter(snapshot ->
                                                                snapshot.recentSounds()
                                                                        .stream()
                                                                        .anyMatch(event ->
                                                                                event.type()
                                                                                                == SemanticEventType
                                                                                                        .SOUND_PLAYED
                                                                                        && event.objects()
                                                                                                .contains(
                                                                                                        soundId)))
                                                        .isPresent()
                                                && other.manager()
                                                        .latestPerception(
                                                                other.name())
                                                        .filter(snapshot ->
                                                                snapshot.gameTick()
                                                                        >= offeredAtTick)
                                                        .isPresent(),
                                        "P3 directed sound was not observed by the target generation",
                                        cleanup,
                                        () -> {
                                            ObservationSnapshot targetSnapshot =
                                                    target.manager()
                                                            .latestPerception(
                                                                    target.name())
                                                            .orElseThrow();
                                            ObservationSnapshot otherSnapshot =
                                                    other.manager()
                                                            .latestPerception(
                                                                    other.name())
                                                            .orElseThrow();
                                            PerceivedEvent perceived =
                                                    targetSnapshot.recentSounds()
                                                            .stream()
                                                            .filter(event ->
                                                                    event.type()
                                                                                    == SemanticEventType
                                                                                            .SOUND_PLAYED
                                                                            && event.objects()
                                                                                    .contains(
                                                                                            soundId))
                                                            .findFirst()
                                                            .orElseThrow();

                                            P2GameTestSupport.require(
                                                    perceived.botId()
                                                                    .equals(target
                                                                            .player()
                                                                            .getUUID())
                                                            && perceived.botGeneration()
                                                                    == target.player()
                                                                            .runtimeHandle()
                                                                            .generation(),
                                                    "Directed sound entered the wrong bot generation");
                                            P2GameTestSupport.require(
                                                    perceived.channel()
                                                                    == PerceptionChannel
                                                                            .AUDIBLE
                                                            && perceived.outcome()
                                                                    == SemanticEventOutcome
                                                                            .COMMITTED,
                                                    "Target sound did not retain AUDIBLE COMMITTED semantics");
                                            P2GameTestSupport.require(
                                                    otherSnapshot.recentSounds()
                                                            .stream()
                                                            .noneMatch(event ->
                                                                    event.authorityEventId()
                                                                                    .equals(
                                                                                            perceived.authorityEventId())
                                                                            || event.objects()
                                                                                    .contains(
                                                                                            soundId)),
                                                    "Directed sound leaked into the non-target sound stream");
                                            P2GameTestSupport.require(
                                                    otherSnapshot.recentEvents()
                                                            .stream()
                                                            .noneMatch(event ->
                                                                    event.authorityEventId()
                                                                            .equals(
                                                                                    perceived.authorityEventId())),
                                                    "Directed sound leaked into the non-target semantic stream");
                                            P2GameTestSupport.require(
                                                    targetSnapshot.snapshotId()
                                                                    > targetBaseline.snapshotId()
                                                            && otherSnapshot.snapshotId()
                                                                    > otherBaseline.snapshotId(),
                                                    "Directed sound assertion reused a baseline snapshot");
                                            cleanup.run();
                                            helper.succeed();
                                        });
                            }));
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void committedPerceivedBlockChangeStalesObservedFact(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos changedBlock =
                helper.absolutePos(new BlockPos(5, 1, 4));
        helper.getLevel().setBlockAndUpdate(
                changedBlock, Blocks.STONE.defaultBlockState());
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P3FactStale",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup = cleanupBot(bot);
        try {
            String targetId = changedBlock.getX()
                    + ","
                    + changedBlock.getY()
                    + ","
                    + changedBlock.getZ();
            awaitSnapshot(
                    helper,
                    bot,
                    snapshot -> snapshot.blocks()
                            .stream()
                            .anyMatch(block -> block.targetId().equals(targetId)
                                    && block.blockId()
                                            .equals("minecraft:stone")),
                    "P3 did not observe the baseline stone block",
                    cleanup,
                    baseline -> {
                        WorldFact observedFact = bot.manager()
                                .recentPerceptionFacts(bot.name(), 256)
                                .stream()
                                .filter(fact -> fact.key()
                                                .namespace()
                                                .equals("block_state")
                                        && fact.key()
                                                .subject()
                                                .equals(targetId)
                                        && fact.status()
                                                == FactStatus.ACTIVE
                                        && "minecraft:stone".equals(
                                                fact.value()
                                                        .fields()
                                                        .get("block_id")))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError(
                                        "Observed stone block did not become an ACTIVE fact"));
                        long mutationTick = helper.getLevel()
                                .getServer()
                                .getTickCount();
                        // 候选记录前态，Tick 末只在世界后态确实改变时发布 COMMITTED。
                        bot.manager()
                                .authorityEventCollector()
                                .recordBlockBreak(
                                        helper.getLevel(),
                                        changedBlock,
                                        helper.getLevel()
                                                .getBlockState(changedBlock),
                                        bot.player());
                        helper.getLevel().setBlockAndUpdate(
                                changedBlock,
                                Blocks.AIR.defaultBlockState());
                        bot.manager()
                                .authorityEventCollector()
                                .flush(mutationTick);

                        awaitSnapshot(
                                helper,
                                bot,
                                snapshot -> snapshot.snapshotId()
                                                > baseline.snapshotId()
                                        && snapshot.recentEvents()
                                                .stream()
                                                .anyMatch(event ->
                                                        event.type()
                                                                        == SemanticEventType
                                                                                .BLOCK_BROKEN
                                                                && event.outcome()
                                                                        == SemanticEventOutcome
                                                                                .COMMITTED
                                                                && event.position()
                                                                        .map(point ->
                                                                                BlockPos.containing(
                                                                                                point.x(),
                                                                                                point.y(),
                                                                                                point.z())
                                                                                        .equals(
                                                                                                changedBlock))
                                                                        .orElse(false)),
                                "P3 did not perceive the committed block change",
                                cleanup,
                                changed -> {
                                    WorldFact invalidated = bot.manager()
                                            .recentPerceptionFacts(
                                                    bot.name(), 256)
                                            .stream()
                                            .filter(fact -> fact.factId()
                                                    .equals(observedFact
                                                            .factId()))
                                            .findFirst()
                                            .orElseThrow(() ->
                                                    new AssertionError(
                                                            "Observed block fact disappeared from audit history"));
                                    P2GameTestSupport.require(
                                            invalidated.status()
                                                    == FactStatus.STALE,
                                            "Committed perceived block change did not stale the observed fact");
                                    P2GameTestSupport.require(
                                            changed.recentEvents()
                                                    .stream()
                                                    .anyMatch(event ->
                                                            event.type()
                                                                            == SemanticEventType
                                                                                    .BLOCK_BROKEN
                                                                    && event.channel()
                                                                            == PerceptionChannel
                                                                                    .SELF),
                                            "Committed block change lacked the bot's SELF perception");
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
