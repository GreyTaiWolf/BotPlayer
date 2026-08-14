package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorAvailability;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorBudget;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorEvidence;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQuery;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorQueryType;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorResourceFilter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorScope;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorSnapshot;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** P5 精确资源 TaskSensor 在资源顶面状态下的窄合同测试。 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5ResourceTaskSensorGameTests {
    private static final String BATCH = "p5_task_sensor_resources";
    private static final int RADIUS = 8;
    private static final int MAXIMUM_BLOCKS = 256;

    private P5ResourceTaskSensorGameTests() {}

    /**
     * 当 body 正站在铁矿顶面、当前请求煤矿时，采样必须扫描共同的下层资源平面；脚下铁矿
     * 不能泄漏为煤矿候选，且有界读取仍要显式标记为 truncated。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void coalQueryFromAnotherResourceTopKeepsExactEvidence(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "task_sensor_resource_lower_layer");
        TestBot bot = fixture.spawn("layer");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            BlockPos formerBodyCell = bot.player().blockPosition();
            P2GameTestSupport.placePlayer(bot.player(), helper.getLevel(),
                    new Vec3(formerBodyCell.getX() + 0.5D,
                            formerBodyCell.getY() + 1.0D,
                            formerBodyCell.getZ() + 0.5D),
                    0.0F);
            helper.getLevel().setBlockAndUpdate(formerBodyCell,
                    Blocks.IRON_ORE.defaultBlockState());
            BlockPos center = bot.player().blockPosition();
            BlockPos coal = center.offset(5, -1, 5);
            helper.getLevel().setBlockAndUpdate(coal,
                    Blocks.COAL_ORE.defaultBlockState());

            TaskSensorSnapshot snapshot = sample(bot, center,
                    TaskSensorResourceFilter.COAL_ORE);
            P2GameTestSupport.require(
                    snapshot.availability() == TaskSensorAvailability.AVAILABLE
                            && snapshot.truncated()
                            && snapshot.evidence().size() == 1,
                    "Cross-resource top scan did not preserve its bounded truncated result");
            assertExactEvidence(snapshot.evidence().getFirst(), coal,
                    "minecraft:coal_ore");
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
        cleanup.run();
        helper.succeed();
    }

    /**
     * The same top-of-resource handoff may be followed by a world recipe instead of another
     * mining fragment. Its exact workstation filter must therefore inspect the shared lower
     * layer without accepting the supporting resource itself as a workstation candidate.
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void craftingTableQueryFromResourceTopKeepsExactEvidence(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "task_sensor_workstation_lower_layer");
        TestBot bot = fixture.spawn("bench");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            BlockPos formerBodyCell = bot.player().blockPosition();
            P2GameTestSupport.placePlayer(bot.player(), helper.getLevel(),
                    new Vec3(formerBodyCell.getX() + 0.5D,
                            formerBodyCell.getY() + 1.0D,
                            formerBodyCell.getZ() + 0.5D),
                    0.0F);
            helper.getLevel().setBlockAndUpdate(formerBodyCell,
                    Blocks.COBBLESTONE.defaultBlockState());
            BlockPos center = bot.player().blockPosition();
            BlockPos table = center.offset(5, -1, 5);
            helper.getLevel().setBlockAndUpdate(table,
                    Blocks.CRAFTING_TABLE.defaultBlockState());

            TaskSensorSnapshot snapshot = sample(bot, center,
                    TaskSensorResourceFilter.CRAFTING_TABLE);
            P2GameTestSupport.require(
                    snapshot.availability() == TaskSensorAvailability.AVAILABLE
                            && snapshot.truncated()
                            && snapshot.evidence().size() == 1,
                    "Resource-top workstation scan did not preserve its bounded truncated result");
            assertExactEvidence(snapshot.evidence().getFirst(), table,
                    "minecraft:crafting_table");
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
        cleanup.run();
        helper.succeed();
    }

    private static TaskSensorSnapshot sample(
            TestBot bot,
            BlockPos center,
            TaskSensorResourceFilter resourceFilter) {
        long generation = bot.player().runtimeHandle().generation();
        TaskSensorRunIdentity identity = new TaskSensorRunIdentity(
                bot.player().getUUID(), generation, UUID.randomUUID(), 1L);
        TaskSensorQuery query = new TaskSensorQuery(
                identity,
                TaskSensorQueryType.RESOURCE_CANDIDATES,
                new TaskSensorScope(bot.player().serverLevel().dimension()
                        .location().toString(), center.getX(), center.getY(),
                        center.getZ(), RADIUS, 1L),
                new TaskSensorBudget(1, 0, MAXIMUM_BLOCKS, 0, 1, 0L),
                resourceFilter);
        MinecraftTaskSensorAdapter adapter = new MinecraftTaskSensorAdapter(
                (botId, requestedGeneration) -> botId.equals(
                                bot.player().getUUID())
                        && requestedGeneration == generation
                                ? Optional.of(bot.player())
                                : Optional.empty(),
                ignored -> Optional.empty());
        return adapter.sample(query, bot.player().serverLevel().getServer()
                .getTickCount());
    }

    private static void assertExactEvidence(
            TaskSensorEvidence evidence,
            BlockPos expectedPosition,
            String expectedBlockId) {
        Map<String, Object> fields = evidence.fields().values();
        P2GameTestSupport.require(
                "resource.candidate".equals(evidence.kind())
                        && fields.keySet().equals(java.util.Set.of(
                                "x", "y", "z", "block"))
                        && expectedBlockId.equals(fields.get("block"))
                        && expectedPosition.getX() == (Integer) fields.get("x")
                        && expectedPosition.getY() == (Integer) fields.get("y")
                        && expectedPosition.getZ() == (Integer) fields.get("z"),
                "Lower-layer scan emitted a non-exact filtered candidate");
    }
}
