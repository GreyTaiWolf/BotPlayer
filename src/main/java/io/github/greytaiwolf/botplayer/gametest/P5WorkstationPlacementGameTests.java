package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.builtin.production.PlaceWorkstation;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionResult;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionPreconditionValidator;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionResolvedNode;
import io.github.greytaiwolf.botplayer.skill.builtin.production.ProductionSkillPlanCompiler;
import io.github.greytaiwolf.botplayer.skill.builtin.production.WorkstationKind;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignal;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalStatus;
import io.github.greytaiwolf.botplayer.skill.core.SkillSignalType;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.reservation.ResourceReservationService;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ActiveBot;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ApprovedOperation;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.CompletionObservation;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ExecutionTicket;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.PreflightObservation;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillNodeHandler.ProductionAction;
import io.github.greytaiwolf.botplayer.skill.runtime.MinecraftProductionSkillPorts;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillNodeContext;
import io.github.greytaiwolf.botplayer.skill.task.MinecraftTaskSensorAdapter;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorLimits;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorRunIdentity;
import io.github.greytaiwolf.botplayer.skill.task.TaskSensorService;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P5A 工作站放置的端到端验收。
 *
 * <p>这些场景不手写 {@code PlaceBlock}：它们由真实 {@link MinecraftProductionSkillPorts}
 * 把 canonical {@code PlaceWorkstation} 的当前 tick 预检降低为严格动作，再交给正常的 action
 * mailbox/backend。这样能同时覆盖冻结 binding、原版 packet、完整状态回读和生产账本扣款。
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5WorkstationPlacementGameTests {
    private static final String BATCH = "p5_workstation_placement";
    private static final int TIMEOUT_TICKS = 240;
    private static final String CRAFTING_TABLE_NODE =
            "place_crafting_table";
    private static final String FURNACE_NODE = "place_furnace";

    private P5WorkstationPlacementGameTests() {
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void workstationPortPlacesExactCraftingTableAndDebitsLedger(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "workstation_port_success");
        TestBot bot = fixture.spawn("place");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareCraftingTableHand(bot);
            PreparedPlacement prepared = preparePlacement(
                    bot, CRAFTING_TABLE_NODE, WorkstationKind.CRAFTING_TABLE);
            WorldInteractionActionSpec.PlaceBlock place = placeBlock(
                    prepared.action());
            BlockPos target = position(place.expectedPlaced());
            P2GameTestSupport.require(
                    bot.player().serverLevel().getBlockState(target).isAir(),
                    "Workstation port did not freeze a strict air target");

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            prepared.action().action(),
                            prepared.action().maximumTicks());
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    TIMEOUT_TICKS - 20,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Port-produced workstation placement did not succeed: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    MinecraftActionSnapshot.block(
                                                    bot.player(), target)
                                            .equals(place.expectedPlaced()),
                                    "Workstation placement did not produce its exact frozen state");
                            P2GameTestSupport.require(
                                    place.expectedPlaced().state()
                                                    .properties().isEmpty(),
                                    "Crafting-table placement did not freeze its complete no-property state");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.CRAFTING_TABLE)
                                            == 1,
                                    "Workstation placement did not debit exactly one crafting table");
                            P2GameTestSupport.require(
                                    evidence(outcome,
                                                    "block.matches_expected")
                                            .equals("true")
                                            && evidence(outcome,
                                                            "item.before_count")
                                                    .equals("2")
                                            && evidence(outcome,
                                                            "item.after_count")
                                                    .equals("1"),
                                    "Strict placement receipt did not retain exact-state and debit evidence");
                            CompletionObservation after = completion(
                                    prepared, outcome);
                            P2GameTestSupport.require(
                                    after.worldBindingSatisfied()
                                            && after.menuBindingSatisfied(),
                                    "Port completion did not re-observe the exact placed target and native menu");
                            P2GameTestSupport.require(
                                    prepared.ticket().expected()
                                                    .expectedPlayerAfter()
                                                    .orElseThrow()
                                            .equals(after.snapshot()
                                                    .playerLedger()),
                                    "Port completion ledger did not equal the reviewed one-table debit");
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

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void workstationPortPlacesExactFurnaceAndDebitsLedger(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "workstation_furnace_success");
        TestBot bot = fixture.spawn("furn");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareWorkstationHand(bot, Items.FURNACE);
            PreparedPlacement prepared = preparePlacement(
                    bot, FURNACE_NODE, WorkstationKind.FURNACE);
            WorldInteractionActionSpec.PlaceBlock place = placeBlock(
                    prepared.action());
            BlockPos target = position(place.expectedPlaced());
            Map<String, String> expectedState = Map.of(
                    "facing", bot.player().getDirection().getOpposite()
                            .getSerializedName(),
                    "lit", "false");
            P2GameTestSupport.require(
                    bot.player().serverLevel().getBlockState(target).isAir(),
                    "Furnace port did not freeze a strict air target");
            P2GameTestSupport.require(
                    place.expectedPlaced().state().properties().equals(
                            expectedState),
                    "Furnace port did not freeze complete facing and unlit state");

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            prepared.action().action(),
                            prepared.action().maximumTicks());
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    TIMEOUT_TICKS - 20,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.SUCCEEDED
                                            && outcome.failureCode()
                                                    == ActionFailureCode.NONE,
                                    "Port-produced furnace placement did not succeed: "
                                            + outcome);
                            BlockTargetFingerprint actual =
                                    MinecraftActionSnapshot.block(
                                            bot.player(), target);
                            P2GameTestSupport.require(
                                    actual.equals(place.expectedPlaced())
                                            && actual.state().blockId().equals(
                                                    WorkstationKind.FURNACE
                                                            .blockId())
                                            && actual.state().properties().equals(
                                                    expectedState),
                                    "Vanilla furnace placement did not retain the complete frozen facing and lit=false state");
                            P2GameTestSupport.require(
                                    inventoryCount(bot, Items.FURNACE) == 1,
                                    "Furnace placement did not debit exactly one furnace");
                            P2GameTestSupport.require(
                                    evidence(outcome,
                                                    "block.matches_expected")
                                            .equals("true")
                                            && evidence(outcome,
                                                            "item.before_count")
                                                    .equals("2")
                                            && evidence(outcome,
                                                            "item.after_count")
                                                    .equals("1"),
                                    "Strict furnace receipt did not retain exact-state and debit evidence");
                            CompletionObservation after = completion(
                                    prepared, outcome);
                            P2GameTestSupport.require(
                                    after.worldBindingSatisfied()
                                            && after.menuBindingSatisfied(),
                                    "Furnace completion did not re-observe its exact target and native menu");
                            P2GameTestSupport.require(
                                    prepared.ticket().expected()
                                                    .expectedPlayerAfter()
                                                    .orElseThrow()
                                            .equals(after.snapshot()
                                                    .playerLedger()),
                                    "Furnace completion ledger did not equal the reviewed one-furnace debit");
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
     * 熔炉 state 的 facing 取决于放置时玩家朝向；preflight 后朝向漂移不能复用旧的冻结 state
     * 发送动作。该断言只观察 plan 的拒绝，不触发任何 world 或 inventory 副作用。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void furnacePlacementRejectsDirectionDriftAfterPreflight(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "workstation_furnace_direction_drift");
        TestBot bot = fixture.spawn("turn");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareWorkstationHand(bot, Items.FURNACE);
            PreparedPlacement prepared = preparePlacement(
                    bot, FURNACE_NODE, WorkstationKind.FURNACE);
            WorldInteractionActionSpec.PlaceBlock place = placeBlock(
                    prepared.action());
            BlockPos target = position(place.expectedPlaced());
            Direction before = bot.player().getDirection();
            turnRight(bot);
            P2GameTestSupport.require(
                    bot.player().getDirection() != before,
                    "Furnace direction-drift fixture did not rotate the bot");
            P2GameTestSupport.require(
                    prepared.ports().plan(prepared.ticket()).isEmpty(),
                    "Furnace port reused a frozen facing after player direction drift");
            P2GameTestSupport.require(
                    bot.player().serverLevel().getBlockState(target).isAir()
                            && inventoryCount(bot, Items.FURNACE) == 2,
                    "Rejected furnace direction drift changed the world or inventory");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    /**
     * 占位发生在 port 冻结 air target 并产生严格 action 后、backend 真正 dispatch 前。后端必须
     * 拒绝该动作，且 completion re-observation 不能把漂移的目标伪装成 production 成功。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void workstationPortRejectsTargetOccupiedAfterPreflight(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper,
                        "workstation_port_occupied_target");
        TestBot bot = fixture.spawn("place");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareCraftingTableHand(bot);
            PreparedPlacement prepared = preparePlacement(
                    bot, CRAFTING_TABLE_NODE, WorkstationKind.CRAFTING_TABLE);
            WorldInteractionActionSpec.PlaceBlock place = placeBlock(
                    prepared.action());
            BlockPos target = position(place.expectedPlaced());
            P2GameTestSupport.require(
                    bot.player().serverLevel().getBlockState(target).isAir(),
                    "Occupied-target fixture did not begin from the port's frozen air state");
            helper.setBlock(relative(helper, target), Blocks.DIRT);

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            prepared.action().action(),
                            prepared.action().maximumTicks());
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    TIMEOUT_TICKS - 20,
                    cleanup,
                    outcome -> {
                        try {
                            P2GameTestSupport.require(
                                    outcome.state() == ActionState.FAILED
                                            && outcome.failureCode()
                                                    == ActionFailureCode
                                                            .PRECONDITION_FAILED,
                                    "Occupied frozen target was not rejected by strict PlaceBlock backend: "
                                            + outcome);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    target).is(Blocks.DIRT)
                                            && inventoryCount(bot,
                                                    Items.CRAFTING_TABLE) == 2,
                                    "Rejected workstation placement changed its occupied target or ledger");
                            CompletionObservation after = completion(
                                    prepared, outcome);
                            P2GameTestSupport.require(
                                    !after.worldBindingSatisfied()
                                            && after.menuBindingSatisfied(),
                                    "Port completion accepted an occupied target as its frozen placement");
                            P2GameTestSupport.require(
                                    !prepared.ticket().expected()
                                                    .expectedPlayerAfter()
                                                    .orElseThrow()
                                            .equals(after.snapshot()
                                                    .playerLedger()),
                                    "Rejected placement unexpectedly satisfied its reviewed debit ledger");
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

    private static PreparedPlacement preparePlacement(
            TestBot bot, String nodeId, WorkstationKind workstation) {
        String operationId = ProductionSkillPlanCompiler
                .operationIdsForProductionNode(nodeId)
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Canonical " + nodeId
                                + " placement has no operation id"));
        ProductionResolvedNode resolved = ProductionSkillPlanCompiler
                .approvedOperation(operationId)
                .orElseThrow(() -> new IllegalStateException(
                        "Canonical " + nodeId
                                + " placement is not approved"));
        P2GameTestSupport.require(
                resolved.node().operation() instanceof PlaceWorkstation
                        && ((PlaceWorkstation) resolved.node().operation())
                                .workstation()
                                == workstation,
                "Canonical placement operation is not the reviewed "
                        + workstation);
        long generation = bot.player().runtimeHandle().generation();
        long currentTick = bot.player().serverLevel().getServer()
                .getTickCount();
        UUID runId = UUID.randomUUID();
        TaskSensorRunIdentity identity = new TaskSensorRunIdentity(
                bot.player().getUUID(), generation, runId, 1L);
        AtomicReference<TaskSensorRunIdentity> currentIdentity =
                new AtomicReference<>(identity);
        TaskSensorService sensors = new TaskSensorService(
                (requestedBotId, requestedRunId) -> {
                    TaskSensorRunIdentity active = currentIdentity.get();
                    return requestedBotId.equals(active.botId())
                            && requestedRunId.equals(active.skillRunId())
                            ? Optional.of(active)
                            : Optional.empty();
                },
                TaskSensorLimits.defaults());
        MinecraftProductionSkillPorts ports = new MinecraftProductionSkillPorts(
                bot.manager()::resolveActive,
                sensors,
                new MinecraftTaskSensorAdapter(
                        bot.manager()::resolveActive,
                        ignored -> Optional.empty()));
        SkillPlan plan = ProductionSkillPlanCompiler.p5aDefault()
                .compileWoodToIronPick(bot.player().getUUID(), 1L);
        SkillPlanNode node = plan.nodes().stream()
                .filter(candidate -> operationId.equals(
                        candidate.parameters().values().get(
                                P5ABuiltinSkillIds
                                        .BOOTSTRAP_IRON_OPERATION_ID_PARAMETER)))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Compiled plan has no " + nodeId + " placement node"));
        SkillNodeContext context = new SkillNodeContext(
                runId,
                bot.player().getUUID(),
                generation,
                1L,
                1L,
                2L,
                node,
                plan.nodes().indexOf(node),
                currentTick,
                currentTick + 180L,
                new ResourceReservationService(8, 180));
        sensors.beginTick(currentTick);
        PreflightObservation before = ports.observeForDispatch(
                new ActiveBot(bot.player().getUUID(), generation),
                context,
                new ApprovedOperation(operationId, resolved)).orElseThrow(
                        () -> new IllegalStateException(
                                "MinecraftProductionSkillPorts could not preflight "
                                        + nodeId + " placement"));
        ProductionPreconditionResult expected =
                new ProductionPreconditionValidator().validate(
                        resolved, before.snapshot());
        P2GameTestSupport.require(
                expected.ready(),
                nodeId + " placement preflight did not produce a ready ledger debit");
        ExecutionTicket ticket = new ExecutionTicket(
                new ActiveBot(bot.player().getUUID(), generation),
                operationId,
                resolved,
                before,
                expected);
        ProductionAction action = ports.plan(ticket).orElseThrow(
                () -> new IllegalStateException(
                        "MinecraftProductionSkillPorts did not lower placement to PlaceBlock"));
        P2GameTestSupport.require(
                action.action().spec()
                        instanceof WorldInteractionActionSpec.PlaceBlock,
                "Workstation port did not produce the strict PlaceBlock action");
        return new PreparedPlacement(bot, sensors, currentIdentity, ports,
                context, ticket, action);
    }

    private static CompletionObservation completion(
            PreparedPlacement prepared, ActionOutcome outcome) {
        long currentTick = prepared.bot().player().serverLevel().getServer()
                .getTickCount();
        long completionRevision = prepared.context().nextStateRevision();
        prepared.currentIdentity().set(new TaskSensorRunIdentity(
                prepared.context().botId(),
                prepared.context().botGeneration(),
                prepared.context().runId(),
                completionRevision));
        prepared.sensors().beginTick(currentTick);
        SkillNodeContext context = new SkillNodeContext(
                prepared.context().runId(),
                prepared.context().botId(),
                prepared.context().botGeneration(),
                prepared.context().planRevision(),
                completionRevision,
                Math.incrementExact(completionRevision),
                prepared.context().node(),
                prepared.context().nodeIndex(),
                currentTick,
                currentTick + 120L,
                prepared.context().reservations());
        return prepared.ports().observeAfterAction(
                prepared.ticket(),
                context,
                signal(prepared, context, outcome)).orElseThrow(
                        () -> new IllegalStateException(
                                "MinecraftProductionSkillPorts did not re-observe placement completion"));
    }

    private static SkillSignal signal(
            PreparedPlacement prepared,
            SkillNodeContext context,
            ActionOutcome outcome) {
        boolean succeeded = outcome.state() == ActionState.SUCCEEDED;
        return new SkillSignal(
                UUID.randomUUID(),
                prepared.context().runId(),
                prepared.context().botId(),
                prepared.context().botGeneration(),
                context.nextStateRevision(),
                UUID.randomUUID(),
                SkillSignalType.ACTION,
                succeeded
                        ? SkillSignalStatus.SUCCEEDED
                        : SkillSignalStatus.FAILED,
                succeeded
                        ? SkillFailureCode.NONE
                        : SkillFailureCode.ACTION_FAILED,
                outcome.evidence(),
                "P5A workstation placement GameTest receipt",
                context.currentTick());
    }

    private static WorldInteractionActionSpec.PlaceBlock placeBlock(
            ProductionAction action) {
        if (action.action().spec()
                instanceof WorldInteractionActionSpec.PlaceBlock place) {
            return place;
        }
        throw new IllegalStateException(
                "Workstation port action was not PlaceBlock");
    }

    private static void prepareCraftingTableHand(TestBot bot) {
        prepareWorkstationHand(bot, Items.CRAFTING_TABLE);
    }

    private static void prepareWorkstationHand(TestBot bot, Item item) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = 0;
        bot.player().getInventory().setItem(0,
                new ItemStack(item, 2));
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void turnRight(TestBot bot) {
        float yaw = bot.player().getYRot() + 90.0F;
        bot.player().setYRot(yaw);
        bot.player().setYHeadRot(yaw);
        bot.player().setYBodyRot(yaw);
    }

    private static BlockPos position(BlockTargetFingerprint target) {
        return new BlockPos(
                target.position().x(),
                target.position().y(),
                target.position().z());
    }

    private static BlockPos relative(
            GameTestHelper helper, BlockPos absolute) {
        return absolute.subtract(helper.absolutePos(BlockPos.ZERO));
    }

    private static String evidence(ActionOutcome outcome, String key) {
        return outcome.evidence().stream()
                .filter(entry -> entry.key().equals(key))
                .findFirst()
                .map(entry -> entry.value())
                .orElseThrow(() -> new IllegalStateException(
                        "Action outcome omitted evidence key " + key));
    }

    private static int inventoryCount(TestBot bot, Item item) {
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

    private record PreparedPlacement(
            TestBot bot,
            TaskSensorService sensors,
            AtomicReference<TaskSensorRunIdentity> currentIdentity,
            MinecraftProductionSkillPorts ports,
            SkillNodeContext context,
            ExecutionTicket ticket,
            ProductionAction action) {
    }
}
