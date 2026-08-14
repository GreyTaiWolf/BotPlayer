package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.BlockHitTarget;
import io.github.greytaiwolf.botplayer.action.interaction.BlockTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.EntityTargetFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Cow;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.Event;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * P2-C acceptance through the normal serverbound player-interaction paths.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P2InteractionAcceptanceGameTests {
    private static final String BATCH = "p2_interaction";
    /*
     * The ordinary interaction batch already uses the configured eight-bot
     * capacity.  Strict placement has its own two-test batch so adding its
     * negative path cannot turn unrelated pickup coverage into a capacity
     * failure before either action is dispatched.
     */
    private static final String STRICT_PLACEMENT_BATCH = "p2_strict_placement";
    /*
     * UUID-bound pickup regression coverage uses a separate batch so its two
     * fixtures cannot exceed the ordinary interaction batch's eight-bot cap.
     */
    private static final String PICKUP_BATCH = "p2_interaction_pickup";

    private P2InteractionAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void useOnPlacesBlockAndConservesHeldStack(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2Place",
                new Vec3(4.5D, 1.0D, 3.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 use-on GameTest completed");
        try {
            BlockPos support =
                    helper.absolutePos(new BlockPos(4, 0, 5));
            BlockPos placed = support.above();
            ItemStack stack = new ItemStack(Items.COBBLESTONE, 2);
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(0, stack);

            BlockHitTarget target = MinecraftActionSnapshot.blockHit(
                    bot.player(),
                    new BlockHitResult(
                            new Vec3(
                                    support.getX() + 0.5D,
                                    support.getY() + 1.0D,
                                    support.getZ() + 0.5D),
                            Direction.UP,
                            support,
                            false));
            ItemStackFingerprint held =
                    MinecraftActionSnapshot.selectedItem(bot.player());
            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .UseOnBlock(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            target,
                                            held)),
                            40);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    120,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            P2GameTestSupport.require(
                                    bot.player()
                                            .serverLevel()
                                            .getBlockState(placed)
                                            .is(Blocks.COBBLESTONE),
                                    "Use-on did not place the declared block");
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .getInventory()
                                                    .getSelected()
                                                    .getCount()
                                            == 1,
                                    "Use-on did not conserve the held stack");
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
     * P5A 工作站放置使用专用严格合同：成功不仅要求有任意世界变化，还必须证明指定相邻
     * 坐标的完整原版 fingerprint、原生背包菜单和主手扣款都与请求一致。
     */
    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = STRICT_PLACEMENT_BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void placeBlockRequiresExactAdjacentStateAndDebit(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2StrictPlace",
                new Vec3(4.5D, 1.0D, 3.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 strict placement GameTest completed");
        try {
            BlockPos relativeSupport = new BlockPos(4, 0, 5);
            BlockPos support = helper.absolutePos(relativeSupport);
            BlockPos relativePlaced = relativeSupport.above();
            BlockPos placed = helper.absolutePos(relativePlaced);
            BlockTargetFingerprint expectedPlaced = expectedPlacedBlock(
                    helper, bot, relativePlaced, Blocks.COBBLESTONE);
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.COBBLESTONE, 2));
            BlockHitTarget anchor = MinecraftActionSnapshot.blockHit(
                    bot.player(),
                    new BlockHitResult(
                            new Vec3(
                                    support.getX() + 0.5D,
                                    support.getY() + 1.0D,
                                    support.getZ() + 0.5D),
                            Direction.UP,
                            support,
                            false));

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec.PlaceBlock(
                                            anchor,
                                            expectedPlaced,
                                            MinecraftActionSnapshot.selectedItem(
                                                    bot.player()))),
                            40);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    120,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            P2GameTestSupport.require(
                                    MinecraftActionSnapshot.block(
                                                    bot.player(), placed)
                                            .equals(expectedPlaced),
                                    "Strict placement reported success without the exact expected block state");
                            P2GameTestSupport.require(
                                    bot.player().containerMenu
                                                    == bot.player().inventoryMenu
                                            && bot.player().inventoryMenu
                                                    .getCarried().isEmpty(),
                                    "Strict placement left a non-native menu or cursor");
                            P2GameTestSupport.require(
                                    bot.player().getInventory().getSelected()
                                                    .is(Items.COBBLESTONE)
                                            && bot.player().getInventory()
                                                    .getSelected().getCount()
                                                    == 1,
                                    "Strict placement did not debit exactly one held block");
                            P2GameTestSupport.require(
                                    evidence(outcome, "block.matches_expected")
                                            .equals("true"),
                                    "Strict placement omitted exact-state evidence");
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
            batch = STRICT_PLACEMENT_BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void placeBlockRejectsOccupiedDestinationBeforeDispatch(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2PlaceBusy",
                new Vec3(4.5D, 1.0D, 3.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 occupied placement GameTest completed");
        try {
            BlockPos relativeSupport = new BlockPos(4, 0, 5);
            BlockPos support = helper.absolutePos(relativeSupport);
            BlockPos relativePlaced = relativeSupport.above();
            BlockPos placed = helper.absolutePos(relativePlaced);
            BlockTargetFingerprint expectedPlaced = expectedPlacedBlock(
                    helper, bot, relativePlaced, Blocks.COBBLESTONE);
            helper.setBlock(relativePlaced, Blocks.DIRT);
            bot.player().getInventory().selected = 0;
            bot.player().getInventory().setItem(
                    0, new ItemStack(Items.COBBLESTONE, 2));
            BlockHitTarget anchor = MinecraftActionSnapshot.blockHit(
                    bot.player(),
                    new BlockHitResult(
                            new Vec3(
                                    support.getX() + 0.5D,
                                    support.getY() + 1.0D,
                                    support.getZ() + 0.5D),
                            Direction.UP,
                            support,
                            false));

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec.PlaceBlock(
                                            anchor,
                                            expectedPlaced,
                                            MinecraftActionSnapshot.selectedItem(
                                                    bot.player()))),
                            40);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    80,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.FAILED,
                                    ActionFailureCode.PRECONDITION_FAILED);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getBlockState(
                                                    placed).is(Blocks.DIRT)
                                            && bot.player().getInventory()
                                                    .getSelected().getCount()
                                                    == 2,
                                    "Rejected placement mutated its occupied destination or held stack");
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void naturalItemUseReportsCompletionFoodLevels(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2UseFood",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 natural item-use GameTest completed");
        try {
            bot.player().getInventory().selected = 0;
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.APPLE, 2));
            bot.player().getFoodData().setFoodLevel(10);
            bot.player().getFoodData().setSaturation(0.0F);
            bot.player().getFoodData().setExhaustion(0.0F);
            int foodBefore =
                    bot.player().getFoodData().getFoodLevel();

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec.UseItem(
                                            WorldInteractionActionSpec.Hand
                                                    .MAIN_HAND,
                                            MinecraftActionSnapshot
                                                    .selectedItem(
                                                            bot.player()),
                                            WorldInteractionActionSpec
                                                    .ItemUseMode
                                                    .FINISH_NATURALLY,
                                            0)),
                            100);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    180,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            int evidenceBefore = Integer.parseInt(
                                    evidence(
                                            outcome,
                                            "player.food_before"));
                            int evidenceAfter = Integer.parseInt(
                                    evidence(
                                            outcome,
                                            "player.food_after"));
                            P2GameTestSupport.require(
                                    evidenceBefore == foodBefore,
                                    "Item-use evidence did not retain the start food level");
                            P2GameTestSupport.require(
                                    evidenceAfter
                                            > evidenceBefore,
                                    "Item-use evidence did not record the completion food level");
                            P2GameTestSupport.require(
                                    evidence(
                                                    outcome,
                                                    "item.before_count")
                                            .equals("2")
                                            && evidence(
                                                            outcome,
                                                            "item.after_count")
                                                    .equals("1"),
                                    "Item-use evidence did not retain the consumed stack counts");
                            String itemBeforeId = evidence(
                                    outcome, "item.before_id");
                            String itemBeforeDamage = evidence(
                                    outcome,
                                    "item.before_damage");
                            String itemBeforeComponents = evidence(
                                    outcome,
                                    "item.before_components");
                            P2GameTestSupport.require(
                                    itemBeforeId.equals(
                                                    "minecraft:apple")
                                            && evidence(
                                                            outcome,
                                                            "item.after_id")
                                                    .equals(itemBeforeId),
                                    "Item-use evidence did not retain the apple item id");
                            P2GameTestSupport.require(
                                    itemBeforeDamage.equals("0")
                                            && evidence(
                                                            outcome,
                                                            "item.after_damage")
                                                    .equals(
                                                            itemBeforeDamage),
                                    "Item-use evidence did not retain the apple damage identity");
                            P2GameTestSupport.require(
                                    !itemBeforeComponents.equals(
                                                    "empty")
                                            && evidence(
                                                            outcome,
                                                            "item.after_components")
                                                    .equals(
                                                            itemBeforeComponents),
                                    "Item-use evidence did not retain the apple components identity");
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void breakBlockUsesPlayerActionAndWorldEvidence(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos target =
                helper.absolutePos(new BlockPos(4, 1, 5));
        helper.setBlock(new BlockPos(4, 1, 5), Blocks.DIRT);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2Break",
                new Vec3(4.5D, 1.0D, 3.4D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 break GameTest completed");
        try {
            bot.player().getInventory().selected = 0;
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.DIAMOND_SHOVEL));

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            breakAction(bot, target),
                            80);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    160,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            P2GameTestSupport.require(
                                    bot.player()
                                            .serverLevel()
                                            .getBlockState(target)
                                            .isAir(),
                                    "Break action reported success without removing the block");
                            P2GameTestSupport.require(
                                    evidence(outcome, "block.after")
                                            .equals("minecraft:air"),
                                    "Break outcome omitted verified world evidence");
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void protectedBreakIsDeniedAndLeavesBlockIntact(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        BlockPos relativeTarget = new BlockPos(4, 1, 5);
        BlockPos target = helper.absolutePos(relativeTarget);
        helper.setBlock(relativeTarget, Blocks.DIRT);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2Protect",
                new Vec3(4.5D, 1.0D, 3.4D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(
                        bot,
                        "P2 protected break GameTest completed");
        try {
            bot.player().getInventory().selected = 0;
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.DIAMOND_SHOVEL));
            AtomicInteger breakEvents = new AtomicInteger();
            Consumer<BlockEvent.BreakEvent> listener = event -> {
                if (event.getPlayer() == bot.player()
                        && event.getPos().equals(target)) {
                    breakEvents.incrementAndGet();
                    event.setCanceled(true);
                }
            };
            NeoForge.EVENT_BUS.addListener(listener);
            cleanup.add(() -> NeoForge.EVENT_BUS.unregister(listener));

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            breakAction(bot, target),
                            80);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    160,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.FAILED,
                                    ActionFailureCode.PERMISSION_DENIED);
                            P2GameTestSupport.require(
                                    breakEvents.get() == 1,
                                    "Protected break did not traverse NeoForge BreakEvent");
                            P2GameTestSupport.require(
                                    bot.player()
                                            .serverLevel()
                                            .getBlockState(target)
                                            .is(Blocks.DIRT),
                                    "A cancelled break changed the protected block");
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void attackEntityProducesVerifiedDamage(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(Cow.class);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2Attack",
                new Vec3(4.5D, 1.0D, 3.0D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 attack GameTest completed");
        try {
            bot.player().getInventory().selected = 0;
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.IRON_SWORD));
            Cow target = helper.spawnWithNoFreeWill(
                    EntityType.COW, new Vec3(4.5D, 1.0D, 4.6D));
            target.moveTo(bot.player().position().add(0.0D, 0.0D, 1.6D));
            target.setDeltaMovement(Vec3.ZERO);
            cleanup.add(target::discard);
            float healthBefore = target.getHealth();
            EntityTargetFingerprint fingerprint =
                    MinecraftActionSnapshot.entity(bot.player(), target);

            P2GameTestSupport.awaitCondition(
                    helper,
                    30,
                    () -> bot.player()
                                    .getAttackStrengthScale(0.5F)
                            >= 1.0F,
                    "Bot attack cooldown did not recover",
                    cleanup,
                    () -> {
                        CompletionStage<ActionOutcome> completion =
                                P2GameTestSupport.submit(
                                        bot,
                                        new WorldInteractionAction(
                                                new WorldInteractionActionSpec
                                                        .AttackEntity(
                                                        fingerprint)),
                                        40);
                        P2GameTestSupport.awaitOutcome(
                                helper,
                                completion,
                                120,
                                cleanup,
                                outcome -> {
                                    try {
                                        requireState(
                                                outcome,
                                                ActionState.SUCCEEDED,
                                                ActionFailureCode.NONE);
                                        P2GameTestSupport.require(
                                                target.isRemoved()
                                                        || target.getHealth()
                                                                < healthBefore
                                                        || target.hurtTime > 0,
                                                "Attack reported success without a target effect");
                                    } finally {
                                        cleanup.run();
                                    }
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
    public static void dropSelectedCreatesItemEntityAndConservesCount(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2Drop",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 drop GameTest completed");
        cleanup.add(
                () -> helper.killAllEntitiesOfClass(
                        ItemEntity.class));
        try {
            bot.player().getInventory().selected = 0;
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.DIAMOND, 3));
            ItemStackFingerprint selected =
                    MinecraftActionSnapshot.selectedItem(bot.player());

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .DropSelected(
                                            false, selected)),
                            40);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    120,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            int held =
                                    bot.player()
                                            .getInventory()
                                            .getSelected()
                                            .getCount();
                            List<ItemEntity> drops =
                                    bot.player()
                                            .serverLevel()
                                            .getEntitiesOfClass(
                                                    ItemEntity.class,
                                                    bot.player()
                                                            .getBoundingBox()
                                                            .inflate(3.0D),
                                                    item -> item.getItem()
                                                            .is(Items.DIAMOND));
                            int dropped = drops.stream()
                                    .mapToInt(item ->
                                            item.getItem().getCount())
                                    .sum();
                            P2GameTestSupport.require(
                                    held == 2 && dropped == 1,
                                    "Drop did not conserve one selected item: held="
                                            + held
                                            + ", dropped="
                                            + dropped);
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void pickupWaitTracksSpecificItemEntityUuid(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2Pickup",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 pickup GameTest completed");
        try {
            Vec3 expectedPosition = bot.player().position();
            ItemEntity expected = new ItemEntity(
                    bot.player().serverLevel(),
                    expectedPosition.x,
                    expectedPosition.y,
                    expectedPosition.z,
                    new ItemStack(Items.EMERALD, 3));
            expected.setDeltaMovement(Vec3.ZERO);
            expected.setPickUpDelay(8);
            P2GameTestSupport.require(
                    bot.player()
                            .serverLevel()
                            .addFreshEntity(expected),
                    "Could not add the expected pickup entity");
            cleanup.add(expected::discard);

            Vec3 decoyPosition =
                    expectedPosition.add(1.5D, 0.0D, 0.0D);
            ItemEntity decoy = new ItemEntity(
                    bot.player().serverLevel(),
                    decoyPosition.x,
                    decoyPosition.y,
                    decoyPosition.z,
                    new ItemStack(Items.EMERALD, 5));
            decoy.setDeltaMovement(Vec3.ZERO);
            decoy.setPickUpDelay(6000);
            P2GameTestSupport.require(
                    bot.player().serverLevel().addFreshEntity(decoy),
                    "Could not add the UUID decoy entity");
            cleanup.add(decoy::discard);
            int emeraldsBefore = countItem(bot, Items.EMERALD);

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .PickupWait(
                                            80,
                                            Optional.of(
                                                    expected.getUUID()))),
                            100);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    180,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .serverLevel()
                                                    .getEntity(
                                                            expected
                                                                    .getUUID())
                                            == null,
                                    "Expected pickup entity still exists");
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .serverLevel()
                                                    .getEntity(
                                                            decoy.getUUID())
                                            == decoy
                                            && !decoy.isRemoved(),
                                    "Pickup consumed the wrong UUID-scoped item entity");
                            int emeraldsAfter =
                                    countItem(bot, Items.EMERALD);
                            P2GameTestSupport.require(
                                    emeraldsAfter - emeraldsBefore == 3,
                                    "Specific pickup did not conserve the expected stack delta: before="
                                            + emeraldsBefore
                                            + ", after="
                                            + emeraldsAfter);
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
            batch = PICKUP_BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void pickupWaitCollectsEveryBoundItemEntity(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2PickupGroup",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 grouped pickup GameTest completed");
        try {
            Vec3 position = bot.player().position();
            ItemEntity emerald = new ItemEntity(
                    bot.player().serverLevel(),
                    position.x,
                    position.y,
                    position.z,
                    new ItemStack(Items.EMERALD, 3));
            emerald.setDeltaMovement(Vec3.ZERO);
            emerald.setPickUpDelay(8);
            ItemEntity diamond = new ItemEntity(
                    bot.player().serverLevel(),
                    position.x,
                    position.y,
                    position.z,
                    new ItemStack(Items.DIAMOND, 2));
            diamond.setDeltaMovement(Vec3.ZERO);
            diamond.setPickUpDelay(8);
            ItemEntity decoy = new ItemEntity(
                    bot.player().serverLevel(),
                    position.x,
                    position.y,
                    position.z,
                    new ItemStack(Items.GOLD_INGOT, 5));
            decoy.setDeltaMovement(Vec3.ZERO);
            decoy.setPickUpDelay(6000);
            P2GameTestSupport.require(
                    bot.player().serverLevel().addFreshEntity(emerald)
                            && bot.player().serverLevel().addFreshEntity(diamond)
                            && bot.player().serverLevel().addFreshEntity(decoy),
                    "Could not add the grouped pickup fixture entities");
            cleanup.add(emerald::discard);
            cleanup.add(diamond::discard);
            cleanup.add(decoy::discard);
            int emeraldsBefore = countItem(bot, Items.EMERALD);
            int diamondsBefore = countItem(bot, Items.DIAMOND);

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec.PickupWait(
                                            80,
                                            List.of(
                                                    emerald.getUUID(),
                                                    diamond.getUUID()))),
                            81);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    180,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.SUCCEEDED,
                                    ActionFailureCode.NONE);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getEntity(
                                                    emerald.getUUID())
                                            == null
                                            && bot.player().serverLevel()
                                                            .getEntity(
                                                                    diamond
                                                                            .getUUID())
                                                    == null,
                                    "Grouped pickup did not collect every exact receipt UUID");
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getEntity(
                                                    decoy.getUUID())
                                            == decoy
                                            && !decoy.isRemoved(),
                                    "Grouped pickup consumed an unbound delayed decoy");
                            P2GameTestSupport.require(
                                    countItem(bot, Items.EMERALD)
                                                    - emeraldsBefore
                                            == 3
                                            && countItem(bot, Items.DIAMOND)
                                                            - diamondsBefore
                                                    == 2,
                                    "Grouped pickup did not conserve every frozen item delta");
                            P2GameTestSupport.require(
                                    evidence(outcome, "entity.ids").equals(
                                            emerald.getUUID() + ","
                                                    + diamond.getUUID())
                                            && evidence(outcome, "entity.count")
                                                    .equals("2"),
                                    "Grouped pickup omitted exact receipt coverage evidence");
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
            batch = PICKUP_BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void exhaustedPickupWaitVerifiesBeforeItsActionEnvelopeExpires(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2PickupBound",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(bot, "P2 pickup wait boundary GameTest completed");
        try {
            Vec3 position = bot.player().position();
            ItemEntity delayed = new ItemEntity(
                    bot.player().serverLevel(),
                    position.x,
                    position.y,
                    position.z,
                    new ItemStack(Items.EMERALD));
            delayed.setDeltaMovement(Vec3.ZERO);
            delayed.setPickUpDelay(6000);
            P2GameTestSupport.require(
                    bot.player().serverLevel().addFreshEntity(delayed),
                    "Could not add the delayed pickup fixture entity");
            cleanup.add(delayed::discard);

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec.PickupWait(
                                            80,
                                            Optional.of(delayed.getUUID()))),
                            81);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    180,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.FAILED,
                                    ActionFailureCode.TARGET_UNAVAILABLE);
                            P2GameTestSupport.require(
                                    bot.player().serverLevel().getEntity(
                                                    delayed.getUUID())
                                            == delayed
                                            && !delayed.isRemoved(),
                                    "The delayed receipt changed before the bounded verification");
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
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void selectingTheAlreadySelectedHotbarSlotFailsClosed(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        TestBot bot = P2GameTestSupport.spawnBot(
                helper,
                null,
                "P2NoopSelect",
                new Vec3(4.5D, 1.0D, 4.5D),
                0.0F);
        P2GameTestSupport.Cleanup cleanup =
                cleanupBot(
                        bot,
                        "P2 no-op hotbar selection GameTest completed");
        try {
            int selectedSlot = 2;
            bot.player().getInventory().selected =
                    selectedSlot;
            bot.player().getInventory().setItem(
                    selectedSlot,
                    new ItemStack(Items.STICK));
            ItemStackFingerprint expected =
                    MinecraftActionSnapshot.item(
                            bot.player(),
                            bot.player()
                                    .getInventory()
                                    .getItem(selectedSlot));

            CompletionStage<ActionOutcome> completion =
                    P2GameTestSupport.submit(
                            bot,
                            new WorldInteractionAction(
                                    new WorldInteractionActionSpec
                                            .SelectHotbar(
                                            selectedSlot,
                                            expected)),
                            40);
            P2GameTestSupport.awaitOutcome(
                    helper,
                    completion,
                    80,
                    cleanup,
                    outcome -> {
                        try {
                            requireState(
                                    outcome,
                                    ActionState.FAILED,
                                    ActionFailureCode
                                            .PRECONDITION_FAILED);
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .getInventory()
                                                    .selected
                                            == selectedSlot
                                            && bot.player()
                                                    .getInventory()
                                                    .getItem(
                                                            selectedSlot)
                                                    .is(
                                                            Items
                                                                    .STICK),
                                    "Rejected no-op selection mutated the hotbar");
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

    private static WorldInteractionAction breakAction(
            TestBot bot, BlockPos target) {
        BlockHitTarget hit = MinecraftActionSnapshot.blockHit(
                bot.player(),
                new BlockHitResult(
                        new Vec3(
                                target.getX() + 0.5D,
                                target.getY() + 0.5D,
                                target.getZ()),
                        Direction.NORTH,
                        target,
                        false));
        return new WorldInteractionAction(
                new WorldInteractionActionSpec.BreakBlock(
                        hit,
                        MinecraftActionSnapshot.selectedItem(
                                bot.player())));
    }

    private static BlockTargetFingerprint expectedPlacedBlock(
            GameTestHelper helper,
            TestBot bot,
            BlockPos relativePosition,
            net.minecraft.world.level.block.Block block) {
        helper.setBlock(relativePosition, block);
        try {
            return MinecraftActionSnapshot.block(
                    bot.player(), helper.absolutePos(relativePosition));
        } finally {
            helper.setBlock(relativePosition, Blocks.AIR);
        }
    }

    private static int countItem(
            TestBot bot, net.minecraft.world.item.Item item) {
        int count = 0;
        for (ItemStack stack : bot.player().getInventory().items) {
            if (stack.is(item)) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static P2GameTestSupport.Cleanup cleanupBot(
            TestBot bot, String reason) {
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        cleanup.add(() -> P2GameTestSupport.removeBot(bot, reason));
        return cleanup;
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

    private static void requireState(
            ActionOutcome outcome,
            ActionState expectedState,
            ActionFailureCode expectedFailureCode) {
        P2GameTestSupport.require(
                outcome.state() == expectedState,
                "Expected action state "
                        + expectedState
                        + " but got "
                        + outcome.state()
                        + ": "
                        + outcome.safeSummary());
        P2GameTestSupport.require(
                outcome.failureCode() == expectedFailureCode,
                "Expected failure code "
                        + expectedFailureCode
                        + " but got "
                        + outcome.failureCode());
    }
}
