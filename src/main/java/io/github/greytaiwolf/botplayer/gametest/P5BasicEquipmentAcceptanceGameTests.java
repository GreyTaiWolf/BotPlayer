package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionKind;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.skill.builtin.P5ABuiltinSkillIds;
import io.github.greytaiwolf.botplayer.skill.core.SkillFailureCode;
import io.github.greytaiwolf.botplayer.skill.core.SkillId;
import io.github.greytaiwolf.botplayer.skill.core.SkillParameters;
import io.github.greytaiwolf.botplayer.skill.core.SkillRunState;
import io.github.greytaiwolf.botplayer.skill.menu.MenuFamily;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlan;
import io.github.greytaiwolf.botplayer.skill.plan.SkillPlanNode;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunSubmission;
import io.github.greytaiwolf.botplayer.skill.runtime.core.SkillRunView;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.Enchantments;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Production acceptance tests for the narrow P5A equipment handlers.
 *
 * <p>They submit a one-node {@link SkillPlan} through the lifecycle manager, so each scenario
 * goes through the registered handler, native 2x2 inventory menu transaction and vanilla
 * {@code clicked()} path. They do not make the generic inventory swap route support equipment.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5BasicEquipmentAcceptanceGameTests {
    private static final String BATCH = "p5_basic_equipment";
    private static final String REJECTION_BATCH = "p5_basic_equipment_rejection";
    private static final int TIMEOUT_TICKS = 240;
    private static final int TERMINAL_WAIT_TICKS = 200;

    private P5BasicEquipmentAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void requestedPickaxeSwapsIntoSelectedHotbarSlot(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper, "basic_equipment_tool");
        TestBot bot = fixture.spawn("tool");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 4);
            bot.player().getInventory().setItem(4, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    9, new ItemStack(Items.IRON_PICKAXE));
            refreshInventoryMenu(bot);

            SkillRunSubmission submission = submitSingleNode(
                    bot,
                    P5ABuiltinSkillIds.EQUIP_BASIC_TOOL,
                    Map.of("tool.kind", "pickaxe"));
            requireAccepted(submission, "Requested pickaxe plan was rejected");

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Requested pickaxe plan did not reach a terminal state",
                    view -> {
                        requireSucceeded(view, "Requested pickaxe plan");
                        P2GameTestSupport.require(
                                bot.player().getMainHandItem().is(Items.IRON_PICKAXE)
                                        && bot.player().getInventory().getItem(4)
                                                .is(Items.IRON_PICKAXE)
                                        && bot.player().getInventory().getItem(9)
                                                .is(Items.STONE),
                                "Native menu did not swap the requested pickaxe into the selected hotbar slot");
                        requireSafeInventoryEndpoint(bot, 4);
                        requireWorldMenuTransaction(bot);
                        P2GameTestSupport.require(
                                count(bot, Items.IRON_PICKAXE) == 1
                                        && count(bot, Items.STONE) == 1,
                                "Requested pickaxe transaction violated item conservation");
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
    public static void requestedExactMainHandItemSwapsThroughNativeMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper, "basic_equipment_exact_main_hand");
        TestBot bot = fixture.spawn("exact");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 2);
            bot.player().getInventory().setItem(2, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    10, new ItemStack(Items.CRAFTING_TABLE));
            refreshInventoryMenu(bot);

            SkillRunSubmission submission = submitSingleNode(
                    bot,
                    P5ABuiltinSkillIds.EQUIP_EXACT_MAIN_HAND,
                    Map.of(
                            P5ABuiltinSkillIds.EXACT_MAIN_HAND_ITEM_ID_PARAMETER,
                            "minecraft:crafting_table"));
            requireAccepted(submission, "Exact main-hand plan was rejected");

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Exact main-hand plan did not reach a terminal state",
                    view -> {
                        requireSucceeded(view, "Exact main-hand plan");
                        P2GameTestSupport.require(
                                bot.player().getMainHandItem()
                                                .is(Items.CRAFTING_TABLE)
                                        && bot.player().getInventory().getItem(2)
                                                .is(Items.CRAFTING_TABLE)
                                        && bot.player().getInventory().getItem(10)
                                                .is(Items.STONE),
                                "Native menu did not swap the approved exact item into the selected hotbar slot");
                        requireSafeInventoryEndpoint(bot, 2);
                        requireWorldMenuTransaction(bot);
                        P2GameTestSupport.require(
                                count(bot, Items.CRAFTING_TABLE) == 1
                                        && count(bot, Items.STONE) == 1,
                                "Exact main-hand transaction violated item conservation");
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
    public static void requestedOrdinaryOffhandSwapsThroughNativeMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper, "basic_equipment_offhand");
        TestBot bot = fixture.spawn("offh");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 6);
            bot.player().getInventory().setItem(6, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    12, new ItemStack(Items.TORCH, 4));
            bot.player().setItemSlot(
                    EquipmentSlot.OFFHAND, new ItemStack(Items.DIRT, 3));
            refreshInventoryMenu(bot);

            SkillRunSubmission submission = submitSingleNode(
                    bot,
                    P5ABuiltinSkillIds.EQUIP_REQUESTED_OFFHAND,
                    Map.of("source.slot", 12));
            requireAccepted(submission, "Requested ordinary offhand plan was rejected");

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Requested ordinary offhand plan did not reach a terminal state",
                    view -> {
                        requireSucceeded(view, "Requested ordinary offhand plan");
                        P2GameTestSupport.require(
                                bot.player().getOffhandItem().is(Items.TORCH)
                                        && bot.player().getOffhandItem().getCount() == 4
                                        && bot.player().getInventory().getItem(12)
                                                .is(Items.DIRT)
                                        && bot.player().getInventory().getItem(12)
                                                .getCount() == 3,
                                "Native menu did not swap the requested ordinary item into the offhand slot");
                        requireSafeInventoryEndpoint(bot, 6);
                        requireWorldMenuTransaction(bot);
                        P2GameTestSupport.require(
                                count(bot, Items.TORCH) == 4
                                        && count(bot, Items.DIRT) == 3
                                        && count(bot, Items.STONE) == 1,
                                "Requested ordinary offhand transaction violated item conservation");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = REJECTION_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void shieldSourceCannotEnterOrdinaryOffhandRoute(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(helper, "basic_equipment_shield_rejection");
        TestBot bot = fixture.spawn("shld");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 3);
            bot.player().getInventory().setItem(3, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(12, new ItemStack(Items.SHIELD));
            bot.player().setItemSlot(
                    EquipmentSlot.OFFHAND, new ItemStack(Items.DIRT, 2));
            refreshInventoryMenu(bot);

            SkillRunSubmission submission = submitSingleNode(
                    bot,
                    P5ABuiltinSkillIds.EQUIP_REQUESTED_OFFHAND,
                    Map.of("source.slot", 12));
            requireAccepted(submission, "Shield rejection plan was rejected before its handler ran");

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Shield rejection plan did not reach a terminal state",
                    view -> {
                        requireFailedWorldChanged(
                                view,
                                "Shield source did not fail closed in the ordinary offhand handler");
                        P2GameTestSupport.require(
                                bot.player().getInventory().getItem(12)
                                                .is(Items.SHIELD)
                                        && bot.player().getOffhandItem().is(Items.DIRT)
                                        && bot.player().getOffhandItem().getCount() == 2,
                                "Rejected shield source changed the ordinary offhand inventory layout");
                        requireSafeInventoryEndpoint(bot, 3);
                        requireNoEquipmentTransaction(bot);
                        P2GameTestSupport.require(
                                count(bot, Items.SHIELD) == 1
                                        && count(bot, Items.DIRT) == 2
                                        && count(bot, Items.STONE) == 1,
                                "Rejected shield source violated item conservation");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = REJECTION_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void bindingRestrictedOffhandTargetCannotBeReplaced(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(
                        helper, "basic_equipment_bound_target");
        TestBot bot = fixture.spawn("boff");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 6);
            bot.player().getInventory().setItem(6, new ItemStack(Items.STONE));
            bot.player().getInventory().setItem(
                    12, new ItemStack(Items.TORCH, 4));
            ItemStack boundOffhand = withBindingCurse(
                    helper, new ItemStack(Items.DIRT, 3));
            ItemStack expectedBoundOffhand = boundOffhand.copy();
            bot.player().setItemSlot(EquipmentSlot.OFFHAND, boundOffhand);
            refreshInventoryMenu(bot);

            SkillRunSubmission submission = submitSingleNode(
                    bot,
                    P5ABuiltinSkillIds.EQUIP_REQUESTED_OFFHAND,
                    Map.of("source.slot", 12));
            requireAccepted(
                    submission,
                    "Binding-restricted offhand replacement plan was rejected before its handler ran");

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Binding-restricted offhand replacement plan did not reach a terminal state",
                    view -> {
                        requireFailedWorldChanged(
                                view,
                                "Binding-restricted offhand target was not failed closed");
                        P2GameTestSupport.require(
                                bot.player().getInventory().getItem(12)
                                                .is(Items.TORCH)
                                        && bot.player().getInventory().getItem(12)
                                                .getCount() == 4
                                        && ItemStack.matches(
                                                expectedBoundOffhand,
                                                bot.player().getOffhandItem()),
                                "Binding-restricted offhand target changed before an action was admitted");
                        requireSafeInventoryEndpoint(bot, 6);
                        requireNoEquipmentTransaction(bot);
                        P2GameTestSupport.require(
                                count(bot, Items.TORCH) == 4
                                        && count(bot, Items.DIRT) == 3
                                        && count(bot, Items.STONE) == 1,
                                "Binding-restricted offhand rejection violated item conservation");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = REJECTION_BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void bindingRestrictedToolCannotEnterSelectedHotbarSlot(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture =
                P5GameTestSupport.isolatedFixture(
                        helper, "basic_equipment_bound_tool");
        TestBot bot = fixture.spawn("btool");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 4);
            bot.player().getInventory().setItem(4, new ItemStack(Items.STONE));
            ItemStack boundPickaxe = withBindingCurse(
                    helper, new ItemStack(Items.IRON_PICKAXE));
            ItemStack expectedBoundPickaxe = boundPickaxe.copy();
            bot.player().getInventory().setItem(9, boundPickaxe);
            refreshInventoryMenu(bot);

            SkillRunSubmission submission = submitSingleNode(
                    bot,
                    P5ABuiltinSkillIds.EQUIP_BASIC_TOOL,
                    Map.of("tool.kind", "pickaxe"));
            requireAccepted(
                    submission,
                    "Binding-restricted tool plan was rejected before its handler ran");

            awaitTerminalRun(
                    helper,
                    bot,
                    submission.runId().orElseThrow(),
                    cleanup,
                    "Binding-restricted tool plan did not reach a terminal state",
                    view -> {
                        requireFailedWorldChanged(
                                view,
                                "Binding-restricted tool was not failed closed");
                        P2GameTestSupport.require(
                                bot.player().getInventory().getItem(4)
                                                .is(Items.STONE)
                                        && ItemStack.matches(
                                                expectedBoundPickaxe,
                                                bot.player().getInventory().getItem(9)),
                                "Binding-restricted tool changed before an action was admitted");
                        requireSafeInventoryEndpoint(bot, 4);
                        requireNoEquipmentTransaction(bot);
                        P2GameTestSupport.require(
                                count(bot, Items.IRON_PICKAXE) == 1
                                        && count(bot, Items.STONE) == 1,
                                "Binding-restricted tool rejection violated item conservation");
                    });
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static SkillRunSubmission submitSingleNode(
            TestBot bot, SkillId skillId, Map<String, Object> parameters) {
        SkillPlan plan = new SkillPlan(
                UUID.randomUUID(),
                bot.player().getUUID(),
                1L,
                List.of(new SkillPlanNode(
                        UUID.randomUUID(),
                        skillId,
                        P5ABuiltinSkillIds.VERSION,
                        new SkillParameters(parameters))),
                List.of());
        return bot.manager().submitSkillPlan(bot.name(), plan);
    }

    private static void awaitTerminalRun(
            GameTestHelper helper,
            TestBot bot,
            UUID runId,
            P2GameTestSupport.Cleanup cleanup,
            String failureMessage,
            Consumer<SkillRunView> verifier) {
        P2GameTestSupport.awaitCondition(
                helper,
                TERMINAL_WAIT_TICKS,
                () -> bot.manager().skillRun(bot.player().getUUID())
                        .filter(view -> view.runId().equals(runId)
                                && view.state().isTerminal())
                        .isPresent(),
                failureMessage,
                cleanup,
                () -> {
                    SkillRunView view = bot.manager()
                            .skillRun(bot.player().getUUID())
                            .filter(candidate -> candidate.runId().equals(runId))
                            .orElseThrow();
                    verifier.accept(view);
                    cleanup.run();
                    helper.succeed();
                });
    }

    private static void prepareEmptyInventory(TestBot bot, int selectedSlot) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = selectedSlot;
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
    }

    private static void refreshInventoryMenu(TestBot bot) {
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void requireAccepted(
            SkillRunSubmission submission, String message) {
        P2GameTestSupport.require(
                submission.status() == SkillRunSubmission.Status.ACCEPTED
                        && submission.runId().isPresent(),
                message + ": " + submission);
    }

    private static void requireSucceeded(SkillRunView view, String subject) {
        P2GameTestSupport.require(
                view.state() == SkillRunState.SUCCEEDED
                        && view.completedNodes() == 1
                        && view.totalNodes() == 1
                        && view.failureCode().isEmpty(),
                subject + " did not succeed with its one-node native-menu run: " + view);
    }

    private static void requireFailedWorldChanged(
            SkillRunView view, String subject) {
        P2GameTestSupport.require(
                view.state() == SkillRunState.FAILED
                        && view.failureCode().orElse(null)
                                == SkillFailureCode.WORLD_CHANGED,
                subject + ": " + view);
    }

    private static void requireSafeInventoryEndpoint(TestBot bot, int selectedSlot) {
        InventoryMenu menu = bot.player().inventoryMenu;
        P2GameTestSupport.require(
                bot.player().getInventory().selected == selectedSlot
                        && bot.player().containerMenu == menu
                        && menu.getClass() == InventoryMenu.class
                        && menu.stillValid(bot.player())
                        && menu.slots.size()
                                == MenuFamily.INVENTORY_2X2.slotCount()
                        && menu.getCarried().isEmpty(),
                "Equipment transaction changed selection, exact native menu, or cursor");
        for (int menuSlot = 0; menuSlot <= 4; menuSlot++) {
            P2GameTestSupport.require(
                    menu.getSlot(menuSlot).getItem().isEmpty(),
                    "Equipment transaction changed a forbidden crafting slot");
        }
    }

    private static void requireWorldMenuTransaction(TestBot bot) {
        UUID botId = bot.player().getUUID();
        long generation = bot.player().runtimeHandle().generation();
        boolean completedWorldMenuTransaction = bot.manager()
                .actionTransitionHistory(512)
                .stream()
                .anyMatch(transition -> transition.botId().equals(botId)
                        && transition.botGeneration() == generation
                        && transition.kind()
                                == ActionKind.WORLD_MENU_TRANSACTION
                        && transition.to() == ActionState.SUCCEEDED);
        boolean usedLegacySwapRoute = bot.manager()
                .actionTransitionHistory(512)
                .stream()
                .anyMatch(transition -> transition.botId().equals(botId)
                        && transition.botGeneration() == generation
                        && (transition.kind()
                                        == ActionKind.INVENTORY_MENU_SWAP
                                || transition.kind()
                                        == ActionKind.SWAP_INVENTORY_HOTBAR));
        P2GameTestSupport.require(
                completedWorldMenuTransaction && !usedLegacySwapRoute,
                "Equipment handler did not complete only through the native world-menu transaction route");
    }

    private static void requireNoEquipmentTransaction(TestBot bot) {
        UUID botId = bot.player().getUUID();
        long generation = bot.player().runtimeHandle().generation();
        boolean submittedInventoryAction = bot.manager()
                .actionTransitionHistory(512)
                .stream()
                .anyMatch(transition -> transition.botId().equals(botId)
                        && transition.botGeneration() == generation
                        && (transition.kind()
                                        == ActionKind.WORLD_MENU_TRANSACTION
                                || transition.kind()
                                        == ActionKind.INVENTORY_MENU_SWAP
                                || transition.kind()
                                        == ActionKind.SWAP_INVENTORY_HOTBAR));
        P2GameTestSupport.require(
                !submittedInventoryAction,
                "Rejected equipment request recorded an inventory action transition");
    }

    private static ItemStack withBindingCurse(
            GameTestHelper helper, ItemStack stack) {
        stack.enchant(
                helper.getLevel()
                        .registryAccess()
                        .lookupOrThrow(Registries.ENCHANTMENT)
                        .getOrThrow(Enchantments.BINDING_CURSE),
                1);
        return stack;
    }

    private static int count(TestBot bot, Item item) {
        int count = 0;
        int size = bot.player().getInventory().getContainerSize();
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = bot.player().getInventory().getItem(slot);
            if (stack.is(item)) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
    }
}
