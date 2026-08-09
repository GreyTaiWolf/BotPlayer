package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * TaskSensor 掉落实体采样的窄合同测试。
 *
 * <p>它只直接调用适配器读取当前服务器线程的已加载近场 ItemEntity，不经过生产 handler，
 * 从而固定候选的纯标量形状、局部范围、稳定排序与截断语义。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5DroppedItemTaskSensorGameTests {
    private static final String BATCH = "p5_task_sensor_dropped_items";

    private P5DroppedItemTaskSensorGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void droppedItemsAreLocalScalarAndDeterministicallyOrdered(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "task_sensor_dropped_items_order");
        TestBot bot = fixture.spawn("drops");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Vec3 center = bot.player().position();
            ItemEntity nearest = addDrop(bot, center,
                    Items.EMERALD, 3, cleanup);
            ItemEntity tiedFirst = addDrop(bot,
                    center.add(1.0D, 0.0D, 0.0D),
                    Items.DIAMOND, 2, cleanup);
            ItemEntity tiedSecond = addDrop(bot,
                    center.add(1.0D, 0.0D, 0.0D),
                    Items.COAL, 1, cleanup);
            ItemEntity outsideScope = addDrop(bot,
                    center.add(3.0D, 0.0D, 0.0D),
                    Items.IRON_INGOT, 4, cleanup);
            TaskSensorScope scope = scope(bot, 2);
            TaskSensorSnapshot snapshot = sample(bot, scope,
                    new TaskSensorBudget(4, 0, 0, 0, 4, 0L));

            P2GameTestSupport.require(
                    snapshot.availability() == TaskSensorAvailability.AVAILABLE
                            && !snapshot.truncated(),
                    "Local dropped-item query unexpectedly became unavailable or truncated");
            List<ItemEntity> visible = List.of(nearest, tiedFirst,
                    tiedSecond);
            List<String> expectedIds = visible.stream()
                    .sorted(Comparator.comparingDouble(
                                    item -> squaredDistance(item, scope))
                            .thenComparing(item -> item.getUUID().toString()))
                    .map(item -> item.getUUID().toString())
                    .toList();
            List<String> actualIds = snapshot.evidence().stream()
                    .map(evidence -> evidence.fields().values()
                            .get("entity.id"))
                    .map(String.class::cast)
                    .toList();
            P2GameTestSupport.require(
                    actualIds.equals(expectedIds)
                            && !actualIds.contains(
                                    outsideScope.getUUID().toString()),
                    "Dropped-item candidates were not exactly local and stably ordered");
            for (TaskSensorEvidence evidence : snapshot.evidence()) {
                assertScalarCandidate(evidence, visible);
            }
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
        cleanup.run();
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void droppedItemsHonorCapsAndDeclareTruncation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "task_sensor_dropped_items_caps");
        TestBot bot = fixture.spawn("caps");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            Vec3 center = bot.player().position();
            ItemEntity nearest = addDrop(bot, center,
                    Items.EMERALD, 1, cleanup);
            addDrop(bot, center.add(1.0D, 0.0D, 0.0D),
                    Items.DIAMOND, 1, cleanup);
            addDrop(bot, center.add(2.0D, 0.0D, 0.0D),
                    Items.COAL, 1, cleanup);
            TaskSensorScope scope = scope(bot, 2);
            TaskSensorSnapshot snapshot = sample(bot, scope,
                    new TaskSensorBudget(2, 0, 0, 0, 1, 0L));

            P2GameTestSupport.require(
                    snapshot.availability() == TaskSensorAvailability.AVAILABLE
                            && snapshot.truncated()
                            && snapshot.evidence().size() == 1,
                    "Dropped-item query did not honor its candidate/evidence caps");
            Object entityId = snapshot.evidence().getFirst()
                    .fields().values().get("entity.id");
            P2GameTestSupport.require(
                    nearest.getUUID().toString().equals(entityId),
                    "Dropped-item cap result did not retain the nearest observed candidate");
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
        cleanup.run();
        helper.succeed();
    }

    private static TaskSensorSnapshot sample(
            TestBot bot, TaskSensorScope scope, TaskSensorBudget budget) {
        long generation = bot.player().runtimeHandle().generation();
        TaskSensorRunIdentity identity = new TaskSensorRunIdentity(
                bot.player().getUUID(), generation, UUID.randomUUID(), 1L);
        TaskSensorQuery query = new TaskSensorQuery(identity,
                TaskSensorQueryType.DROPPED_ITEMS, scope, budget);
        MinecraftTaskSensorAdapter adapter = new MinecraftTaskSensorAdapter(
                (botId, requestedGeneration) -> botId.equals(
                                bot.player().getUUID())
                        && requestedGeneration == generation
                                ? Optional.of(bot.player())
                                : Optional.empty(),
                ignored -> Optional.empty());
        return adapter.sample(query, bot.player().serverLevel()
                .getServer().getTickCount());
    }

    private static TaskSensorScope scope(TestBot bot, int radius) {
        BlockPos position = bot.player().blockPosition();
        return new TaskSensorScope(bot.player().serverLevel().dimension()
                .location().toString(), position.getX(), position.getY(),
                position.getZ(), radius, 1L);
    }

    private static ItemEntity addDrop(
            TestBot bot,
            Vec3 position,
            Item item,
            int count,
            P2GameTestSupport.Cleanup cleanup) {
        ItemEntity drop = new ItemEntity(bot.player().serverLevel(),
                position.x, position.y, position.z,
                new ItemStack(item, count));
        drop.setDeltaMovement(Vec3.ZERO);
        drop.setPickUpDelay(6_000);
        P2GameTestSupport.require(
                bot.player().serverLevel().addFreshEntity(drop),
                "Could not add local dropped-item TaskSensor fixture");
        cleanup.add(drop::discard);
        return drop;
    }

    private static void assertScalarCandidate(
            TaskSensorEvidence evidence, List<ItemEntity> expected) {
        P2GameTestSupport.require(
                "dropped_item.candidate".equals(evidence.kind())
                        && evidence.fields().values().keySet().equals(Set.of(
                                "entity.id", "item", "count", "x", "y",
                                "z")),
                "Dropped-item TaskSensor leaked non-scalar or unexpected evidence");
        String entityId = (String) evidence.fields().values().get("entity.id");
        ItemEntity item = expected.stream()
                .filter(candidate -> candidate.getUUID().toString()
                        .equals(entityId))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "Dropped-item TaskSensor returned an unknown entity id"));
        Map<String, Object> fields = evidence.fields().values();
        BlockPos position = item.blockPosition();
        P2GameTestSupport.require(
                BuiltInRegistries.ITEM.getKey(item.getItem().getItem())
                                .toString().equals(fields.get("item"))
                        && item.getItem().getCount() == (Integer) fields.get(
                                "count")
                        && position.getX() == (Integer) fields.get("x")
                        && position.getY() == (Integer) fields.get("y")
                        && position.getZ() == (Integer) fields.get("z"),
                "Dropped-item TaskSensor candidate fields did not remain scalar current facts");
    }

    private static double squaredDistance(
            ItemEntity item, TaskSensorScope scope) {
        double deltaX = item.getX() - (scope.centerX() + 0.5D);
        double deltaY = item.getY() - (scope.centerY() + 0.5D);
        double deltaZ = item.getZ() - (scope.centerZ() + 0.5D);
        return deltaX * deltaX + deltaY * deltaY + deltaZ * deltaZ;
    }
}
