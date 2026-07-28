package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.ActionFailureCode;
import io.github.greytaiwolf.botplayer.action.ActionOutcome;
import io.github.greytaiwolf.botplayer.action.ActionState;
import io.github.greytaiwolf.botplayer.action.WorldInteractionAction;
import io.github.greytaiwolf.botplayer.action.interaction.ItemStackFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.WorldInteractionActionSpec;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.config.BotPlayerConfig;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryLayout;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import io.github.greytaiwolf.botplayer.inventory.BotInventorySessionManager;
import io.netty.buffer.Unpooled;
import java.util.concurrent.CompletionStage;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.protocol.game.ServerboundInteractPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.network.connection.ConnectionType;

/**
 * P2-D acceptance for the real 41-slot bot inventory and its single-viewer transaction.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P2InventoryAcceptanceGameTests {
    private static final String BATCH = "p2_inventory";

    private P2InventoryAcceptanceGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void emptyMainHandOpensButOffhandDoesNot(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            ServerPlayer viewer = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.5D, 1.0D, 3.4D));
            trackViewer(cleanup, viewer);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    viewer,
                    "P2InvOpen",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            trackBot(cleanup, bot);
            bot.player().getInventory().selected = 6;
            viewer.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            viewer.setItemInHand(
                    InteractionHand.OFF_HAND,
                    new ItemStack(Items.SHIELD));

            interact(viewer, bot, InteractionHand.OFF_HAND);
            P2GameTestSupport.require(
                    !(viewer.containerMenu instanceof BotInventoryMenu),
                    "Off-hand interaction unexpectedly opened the bot inventory");

            interact(viewer, bot, InteractionHand.MAIN_HAND);
            P2GameTestSupport.require(
                    viewer.containerMenu instanceof BotInventoryMenu,
                    "Empty main-hand interaction did not open the bot inventory");
            BotInventoryMenu menu =
                    (BotInventoryMenu) viewer.containerMenu;
            P2GameTestSupport.require(
                    menu.slots.size()
                            == BotInventoryLayout.TOTAL_MENU_SLOTS,
                    "Opened bot menu did not expose 77 slots");
            requireSlotPosition(menu, 0, 8, 8);
            requireSlotPosition(menu, 3, 8, 62);
            requireSlotPosition(menu, 4, 77, 62);
            requireSlotPosition(menu, 5, 8, 84);
            requireSlotPosition(menu, 31, 152, 120);
            requireSlotPosition(menu, 32, 8, 142);
            requireSlotPosition(menu, 40, 152, 142);
            requireSlotPosition(menu, 41, 8, 174);
            requireSlotPosition(menu, 67, 152, 210);
            requireSlotPosition(menu, 68, 8, 232);
            requireSlotPosition(menu, 76, 152, 232);
            P2GameTestSupport.require(
                    menu.botEntityId() == bot.player().getId(),
                    "Opened bot menu did not expose the current bot entity ID");
            P2GameTestSupport.require(
                    menu.selectedBotHotbar() == 6,
                    "Opened bot menu did not expose the selected bot hotbar slot");

            bot.player().getInventory().selected = 99;
            menu.broadcastChanges();
            P2GameTestSupport.require(
                    menu.selectedBotHotbar() == 8,
                    "Bot hotbar synchronization did not clamp its upper bound");
            bot.player().getInventory().selected = -3;
            menu.broadcastChanges();
            P2GameTestSupport.require(
                    menu.selectedBotHotbar() == 0,
                    "Bot hotbar synchronization did not clamp its lower bound");
            bot.player().getInventory().selected = 6;

            RegistryFriendlyByteBuf clientData = new RegistryFriendlyByteBuf(
                    Unpooled.buffer(),
                    viewer.registryAccess(),
                    ConnectionType.NEOFORGE);
            try {
                clientData.writeVarInt(bot.player().getId());
                BotInventoryMenu clientMenu = new BotInventoryMenu(
                        menu.containerId,
                        viewer.getInventory(),
                        clientData);
                P2GameTestSupport.require(
                        clientMenu.botEntityId() == bot.player().getId(),
                        "Client menu did not decode the bot entity ID");
                clientMenu.setData(0, 6);
                P2GameTestSupport.require(
                        clientMenu.selectedBotHotbar() == 6,
                        "Client menu did not accept the synchronized hotbar slot");
                clientMenu.setData(0, 99);
                P2GameTestSupport.require(
                        clientMenu.selectedBotHotbar() == 8,
                        "Client menu did not clamp the synchronized upper bound");
                clientMenu.setData(0, -3);
                P2GameTestSupport.require(
                        clientMenu.selectedBotHotbar() == 0,
                        "Client menu did not clamp the synchronized lower bound");
            } finally {
                clientData.release();
            }
        } finally {
            cleanup.run();
        }
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void inventoryPermissionDeniesStrangerAndAllowsOp(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            ServerPlayer owner = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.0D, 1.0D, 3.2D));
            trackViewer(cleanup, owner);
            ServerPlayer stranger = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(5.0D, 1.0D, 3.2D));
            trackViewer(cleanup, stranger);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    owner,
                    "P2InvPerm",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            trackBot(cleanup, bot);
            owner.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            stranger.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);

            interact(stranger, bot, InteractionHand.MAIN_HAND);
            P2GameTestSupport.require(
                    !(stranger.containerMenu instanceof BotInventoryMenu),
                    "A non-owner opened the bot inventory without permission");

            helper.getLevel()
                    .getServer()
                    .getPlayerList()
                    .op(stranger.getGameProfile());
            cleanup.add(() -> helper.getLevel()
                    .getServer()
                    .getPlayerList()
                    .deop(stranger.getGameProfile()));
            interact(stranger, bot, InteractionHand.MAIN_HAND);
            P2GameTestSupport.require(
                    stranger.containerMenu instanceof BotInventoryMenu,
                    "An operator could not open the bot inventory");
        } finally {
            cleanup.run();
        }
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void secondViewerIsRejectedBySingleViewerLock(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            ServerPlayer owner = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.0D, 1.0D, 3.2D));
            trackViewer(cleanup, owner);
            ServerPlayer secondViewer =
                    P2GameTestSupport.spawnViewer(
                            helper, new Vec3(5.0D, 1.0D, 3.2D));
            trackViewer(cleanup, secondViewer);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    owner,
                    "P2InvLock",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            trackBot(cleanup, bot);
            owner.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            secondViewer.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            helper.getLevel()
                    .getServer()
                    .getPlayerList()
                    .op(secondViewer.getGameProfile());
            cleanup.add(() -> helper.getLevel()
                    .getServer()
                    .getPlayerList()
                    .deop(secondViewer.getGameProfile()));

            interact(owner, bot, InteractionHand.MAIN_HAND);
            P2GameTestSupport.require(
                    owner.containerMenu instanceof BotInventoryMenu,
                    "Owner could not acquire the inventory write lock");
            interact(secondViewer, bot, InteractionHand.MAIN_HAND);
            BotInventorySessionManager.OpenStatus directStatus =
                    bot.manager()
                            .openInventory(
                                    secondViewer, bot.player());
            P2GameTestSupport.require(
                    !(secondViewer.containerMenu
                            instanceof BotInventoryMenu),
                    "A second viewer bypassed the single-viewer lock");
            P2GameTestSupport.require(
                    directStatus
                            == BotInventorySessionManager.OpenStatus
                                    .BOT_LOCKED,
                    "Second viewer rejection was not BOT_LOCKED: "
                            + directStatus);
        } finally {
            cleanup.run();
        }
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void distanceAndLifecycleCloseOpenMenu(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            Vec3 viewerRelative =
                    new Vec3(4.5D, 1.0D, 3.4D);
            ServerPlayer viewer =
                    P2GameTestSupport.spawnViewer(
                            helper, viewerRelative);
            trackViewer(cleanup, viewer);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    viewer,
                    "P2InvClose",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            trackBot(cleanup, bot);
            viewer.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            interact(viewer, bot, InteractionHand.MAIN_HAND);
            P2GameTestSupport.require(
                    viewer.containerMenu instanceof BotInventoryMenu,
                    "Owner could not open the menu before distance validation");

            Vec3 far = bot.player()
                    .position()
                    .add(
                            BotPlayerConfig.INVENTORY_VIEW_DISTANCE.get()
                                    + 4.0D,
                            0.0D,
                            0.0D);
            moveViewer(viewer, far);
            P2GameTestSupport.awaitCondition(
                    helper,
                    20,
                    () -> !(viewer.containerMenu
                            instanceof BotInventoryMenu),
                    "Inventory menu stayed open outside the distance bound",
                    cleanup,
                    () -> {
                        try {
                            Vec3 near =
                                    helper.absoluteVec(
                                            viewerRelative);
                            moveViewer(viewer, near);
                            interact(
                                    viewer,
                                    bot,
                                    InteractionHand.MAIN_HAND);
                            P2GameTestSupport.require(
                                    viewer.containerMenu
                                            instanceof BotInventoryMenu,
                                    "Owner could not reopen the menu after returning in range");
                            boolean removed =
                                    bot.manager().removeByName(
                                            bot.name(),
                                            net.minecraft.network.chat
                                                    .Component.literal(
                                                    "P2 inventory lifecycle GameTest"));
                            P2GameTestSupport.require(
                                    removed,
                                    "Lifecycle removal did not remove the test bot");
                            P2GameTestSupport.require(
                                    !(viewer.containerMenu
                                            instanceof BotInventoryMenu),
                                    "Bot unload did not close its inventory menu");
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
    public static void seventySevenSlotsShiftMoveConservesItems(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        try {
            ServerPlayer viewer = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.5D, 1.0D, 3.4D));
            trackViewer(cleanup, viewer);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    viewer,
                    "P2InvShift",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            trackBot(cleanup, bot);
            bot.player()
                    .getInventory()
                    .setItem(9, new ItemStack(Items.IRON_INGOT, 3));
            viewer.getInventory()
                    .setItem(9, new ItemStack(Items.GOLD_INGOT, 4));
            viewer.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            interact(viewer, bot, InteractionHand.MAIN_HAND);

            P2GameTestSupport.require(
                    viewer.containerMenu instanceof BotInventoryMenu,
                    "Owner could not open the menu for shift-move");
            BotInventoryMenu menu =
                    (BotInventoryMenu) viewer.containerMenu;
            P2GameTestSupport.require(
                    menu.slots.size()
                            == BotInventoryLayout.TOTAL_MENU_SLOTS,
                    "Bot inventory menu is not the declared 77-slot layout");
            int totalBefore = totalCount(bot.player(), viewer);
            ItemStack movedToBot = menu.quickMoveStack(
                    viewer, BotInventoryLayout.VIEWER_MAIN_START);
            P2GameTestSupport.require(
                    movedToBot.is(Items.GOLD_INGOT)
                            && movedToBot.getCount() == 4,
                    "Viewer-to-bot shift move returned the wrong stack");
            P2GameTestSupport.require(
                    countItem(bot.player(), Items.GOLD_INGOT) == 4,
                    "Viewer-to-bot shift move did not reach the bot inventory");

            ItemStack movedToViewer = menu.quickMoveStack(
                    viewer, BotInventoryLayout.BOT_MAIN_START);
            P2GameTestSupport.require(
                    movedToViewer.is(Items.IRON_INGOT)
                            && movedToViewer.getCount() == 3,
                    "Bot-to-viewer shift move returned the wrong stack");
            P2GameTestSupport.require(
                    countItem(viewer, Items.IRON_INGOT) == 3,
                    "Bot-to-viewer shift move did not reach the viewer inventory");
            P2GameTestSupport.require(
                    totalCount(bot.player(), viewer) == totalBefore,
                    "Shift move violated cross-inventory item conservation");
        } finally {
            cleanup.run();
        }
        helper.succeed();
    }

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = P2GameTestSupport.TIMEOUT_TICKS)
    public static void openViewerBlocksActionInventoryMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        helper.killAllEntitiesOfClass(ItemEntity.class);
        P2GameTestSupport.Cleanup cleanup =
                new P2GameTestSupport.Cleanup();
        cleanup.add(
                () -> helper.killAllEntitiesOfClass(
                        ItemEntity.class));
        try {
            ServerPlayer viewer = P2GameTestSupport.spawnViewer(
                    helper, new Vec3(4.5D, 1.0D, 3.4D));
            trackViewer(cleanup, viewer);
            TestBot bot = P2GameTestSupport.spawnBot(
                    helper,
                    viewer,
                    "P2InvGate",
                    new Vec3(4.5D, 1.0D, 4.5D),
                    180.0F);
            trackBot(cleanup, bot);
            bot.player().getInventory().selected = 0;
            bot.player()
                    .getInventory()
                    .setItem(0, new ItemStack(Items.DIAMOND, 3));
            viewer.setItemInHand(
                    InteractionHand.MAIN_HAND, ItemStack.EMPTY);
            interact(viewer, bot, InteractionHand.MAIN_HAND);
            P2GameTestSupport.require(
                    viewer.containerMenu instanceof BotInventoryMenu,
                    "Owner could not acquire the inventory write lock");
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
                                    ActionState.FAILED,
                                    ActionFailureCode.CHANNEL_BUSY);
                            P2GameTestSupport.require(
                                    bot.player()
                                                    .getInventory()
                                                    .getSelected()
                                                    .getCount()
                                            == 3,
                                    "Write-locked action changed the bot inventory");
                            P2GameTestSupport.require(
                                    bot.player()
                                            .serverLevel()
                                            .getEntitiesOfClass(
                                                    ItemEntity.class,
                                                    bot.player()
                                                            .getBoundingBox()
                                                            .inflate(3.0D),
                                                    item -> item.getItem()
                                                            .is(Items.DIAMOND))
                                            .isEmpty(),
                                    "Write-locked action created a dropped item");
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

    private static void interact(
            ServerPlayer viewer,
            TestBot bot,
            InteractionHand hand) {
        P2GameTestSupport.require(
                viewer.serverLevel()
                        == bot.player().serverLevel(),
                "Viewer and bot are in different levels");
        P2GameTestSupport.require(
                viewer.getServer()
                                .getPlayerList()
                                .getPlayer(viewer.getUUID())
                        == viewer,
                "Viewer is not the authoritative PlayerList instance");
        P2GameTestSupport.require(
                bot.player()
                                .getServer()
                                .getPlayerList()
                                .getPlayer(bot.player().getUUID())
                        == bot.player(),
                "Bot is not the authoritative PlayerList instance");
        P2GameTestSupport.require(
                viewer.distanceToSqr(bot.player()) <= 16.0D,
                "Viewer is outside interaction range: viewer="
                        + viewer.position()
                        + ", bot="
                        + bot.player().position());
        viewer.connection.handleInteract(
                ServerboundInteractPacket.createInteractionPacket(
                        bot.player(), false, hand));
    }

    private static void moveViewer(ServerPlayer viewer, Vec3 position) {
        P2GameTestSupport.placePlayer(
                viewer,
                viewer.serverLevel(),
                position,
                viewer.getYRot());
    }

    private static void requireSlotPosition(
            BotInventoryMenu menu,
            int menuIndex,
            int expectedX,
            int expectedY) {
        P2GameTestSupport.require(
                menu.slots.get(menuIndex).x == expectedX
                        && menu.slots.get(menuIndex).y == expectedY,
                "Menu slot "
                        + menuIndex
                        + " was at ("
                        + menu.slots.get(menuIndex).x
                        + ", "
                        + menu.slots.get(menuIndex).y
                        + ") instead of ("
                        + expectedX
                        + ", "
                        + expectedY
                        + ")");
    }

    private static int totalCount(
            ServerPlayer bot, ServerPlayer viewer) {
        int total = 0;
        for (int slot = 0;
                slot < bot.getInventory().getContainerSize();
                slot++) {
            total = Math.addExact(
                    total,
                    bot.getInventory().getItem(slot).getCount());
        }
        for (int slot = 0;
                slot < viewer.getInventory().getContainerSize();
                slot++) {
            total = Math.addExact(
                    total,
                    viewer.getInventory().getItem(slot).getCount());
        }
        return total;
    }

    private static int countItem(
            ServerPlayer player, Item item) {
        int count = 0;
        for (int slot = 0;
                slot < player.getInventory().getContainerSize();
                slot++) {
            ItemStack stack = player.getInventory().getItem(slot);
            if (stack.is(item)) {
                count = Math.addExact(count, stack.getCount());
            }
        }
        return count;
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

    private static void trackViewer(
            P2GameTestSupport.Cleanup cleanup,
            ServerPlayer viewer) {
        cleanup.add(
                () -> P2GameTestSupport.disconnectViewer(viewer));
        cleanup.add(() -> {
            if (viewer.containerMenu != viewer.inventoryMenu) {
                viewer.closeContainer();
            }
        });
    }

    private static void trackBot(
            P2GameTestSupport.Cleanup cleanup, TestBot bot) {
        cleanup.add(() -> P2GameTestSupport.removeBot(
                bot, "P2 inventory GameTest completed"));
    }
}
