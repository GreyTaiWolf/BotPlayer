package io.github.greytaiwolf.botplayer.gametest;

import io.github.greytaiwolf.botplayer.BotPlayer;
import io.github.greytaiwolf.botplayer.action.interaction.BlockStateFingerprint;
import io.github.greytaiwolf.botplayer.action.interaction.ResourceId;
import io.github.greytaiwolf.botplayer.action.interaction.menu.InventoryMenuSnapshot;
import io.github.greytaiwolf.botplayer.action.minecraft.MinecraftActionSnapshot;
import io.github.greytaiwolf.botplayer.building.blueprint.Blueprint;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintCell;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintMaterialClass;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintOffset;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintPlacementRole;
import io.github.greytaiwolf.botplayer.building.blueprint.BlueprintReplacePolicy;
import io.github.greytaiwolf.botplayer.building.material.BlueprintPlaceableItemEvidence;
import io.github.greytaiwolf.botplayer.building.material.ConstructionMaterialAvailability;
import io.github.greytaiwolf.botplayer.building.material.PlaceableItemEvidence;
import io.github.greytaiwolf.botplayer.building.material.minecraft.MinecraftConstructionMaterialAvailabilitySampler;
import io.github.greytaiwolf.botplayer.gametest.P2GameTestSupport.TestBot;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * In-game coverage for the narrow P5D-A7 read-only own-inventory material observation.
 *
 * <p>No test in this holder reserves, moves, consumes, or places a material. The only direct inventory
 * edits create the GameTest fixture; the sampler itself must leave the inventory and world unchanged.
 */
@GameTestHolder(BotPlayer.MOD_ID)
@PrefixGameTestTemplate(false)
public final class P5ConstructionMaterialAvailabilityGameTests {
    private static final String BATCH = "p5_construction_material_availability";
    private static final int TIMEOUT_TICKS = 40;
    private static final UUID BLUEPRINT_ID = UUID.fromString(
            "00000000-0000-0000-0000-000000000d47");
    private static final ResourceId STONE = new ResourceId("minecraft:stone");
    private static final ResourceId OAK_STAIRS = new ResourceId("minecraft:oak_stairs");
    private static final ResourceId STICK = new ResourceId("minecraft:stick");
    private static final BlockStateFingerprint STONE_STATE = new BlockStateFingerprint(STONE,
            Map.of());
    private static final BlockStateFingerprint OAK_STAIRS_NORTH = new BlockStateFingerprint(
            OAK_STAIRS, Map.of(
                    "facing", "north",
                    "half", "bottom",
                    "shape", "straight",
                    "waterlogged", "false"));

    private P5ConstructionMaterialAvailabilityGameTests() {}

    @GameTest(
            template = P2GameTestSupport.TEMPLATE,
            batch = BATCH,
            timeoutTicks = TIMEOUT_TICKS)
    public static void samplesSufficientCanonicalMainAndHotbarMaterialsWithoutMutation(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture = P5GameTestSupport.isolatedFixture(
                helper, "construction_material_sufficient");
        TestBot bot = fixture.spawn("bot");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 4);
            bot.player().getInventory().setItem(0, new ItemStack(Items.STONE, 2));
            bot.player().getInventory().setItem(9, new ItemStack(Items.STONE, 1));
            bot.player().getInventory().setItem(10, new ItemStack(Items.OAK_STAIRS, 1));
            refreshInventoryMenu(bot);

            BlueprintPlaceableItemEvidence declared = declared(1L, List.of(
                    cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT),
                    cell(new BlueprintOffset(1, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.TEMPORARY),
                    cell(new BlueprintOffset(2, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT),
                    cell(new BlueprintOffset(3, 0, 0), OAK_STAIRS_NORTH,
                            BlueprintMaterialClass.PERMANENT))));
            InventoryMenuSnapshot beforeInventory = MinecraftActionSnapshot.inventoryMenu(bot.player());
            BlockPos witness = helper.absolutePos(new BlockPos(3, 1, 3));
            BlockState beforeWitness = helper.getLevel().getBlockState(witness);
            long beforeTick = helper.getLevel().getServer().getTickCount();

            ConstructionMaterialAvailability availability =
                    MinecraftConstructionMaterialAvailabilitySampler.sample(bot.player(), declared);
            long afterTick = helper.getLevel().getServer().getTickCount();
            InventoryMenuSnapshot afterInventory = MinecraftActionSnapshot.inventoryMenu(bot.player());

            P2GameTestSupport.require(
                    availability.status() == ConstructionMaterialAvailability.Status.AVAILABLE
                            && availability.isAvailable()
                            && availability.botId().equals(bot.player().getUUID())
                            && availability.botGeneration()
                                    == bot.player().runtimeHandle().generation()
                            && availability.declaredEvidence().equals(declared)
                            && availability.observedTick() >= beforeTick
                            && availability.observedTick() <= afterTick
                            && availability.inventoryMenuFence().isPresent(),
                    "Material observation did not preserve its exact bot, declaration, tick, and menu identity");
            P2GameTestSupport.require(
                    availability.inventoryMenuFence().orElseThrow().containerId()
                                    == beforeInventory.containerId()
                            && availability.inventoryMenuFence().orElseThrow().stateId()
                                    == beforeInventory.stateId()
                            && availability.inventoryMenuFence().orElseThrow().selectedHotbar()
                                    == beforeInventory.selectedHotbar(),
                    "Material observation did not return the captured native menu fence");
            requireFinding(availability, STONE, 3, 3, 0);
            requireFinding(availability, OAK_STAIRS, 1, 1, 0);
            P2GameTestSupport.require(beforeInventory.equals(afterInventory)
                            && helper.getLevel().getBlockState(witness).equals(beforeWitness),
                    "Read-only material observation changed the bot inventory or witness block");
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
            timeoutTicks = TIMEOUT_TICKS)
    public static void reportsCanonicalShortageAcrossPermanentAndTemporaryItems(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture = P5GameTestSupport.isolatedFixture(
                helper, "construction_material_shortage");
        TestBot bot = fixture.spawn("bot");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 0);
            bot.player().getInventory().setItem(0, new ItemStack(Items.STONE, 3));
            refreshInventoryMenu(bot);
            BlueprintPlaceableItemEvidence declared = declared(2L, List.of(
                    cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT),
                    cell(new BlueprintOffset(1, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT),
                    cell(new BlueprintOffset(2, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.TEMPORARY),
                    cell(new BlueprintOffset(3, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.TEMPORARY),
                    cell(new BlueprintOffset(4, 0, 0), OAK_STAIRS_NORTH,
                            BlueprintMaterialClass.PERMANENT))));

            ConstructionMaterialAvailability availability =
                    MinecraftConstructionMaterialAvailabilitySampler.sample(bot.player(), declared);

            P2GameTestSupport.require(
                    availability.status() == ConstructionMaterialAvailability.Status.SHORTAGE
                            && !availability.isAvailable()
                            && availability.inventoryMenuFence().isPresent(),
                    "Insufficient materials were not reported as a usable shortage observation");
            requireFinding(availability, OAK_STAIRS, 1, 0, 1);
            requireFinding(availability, STONE, 4, 3, 1);
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
            timeoutTicks = TIMEOUT_TICKS)
    public static void excludesEquipmentAndChangedStacksAndRejectsCursorAsUnavailable(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture = P5GameTestSupport.isolatedFixture(
                helper, "construction_material_slot_fence");
        TestBot bot = fixture.spawn("bot");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 0);
            bot.player().getInventory().setItem(0, new ItemStack(Items.STONE, 1));
            bot.player().getInventory().setItem(36, new ItemStack(Items.STONE, 4));
            bot.player().getInventory().setItem(40, new ItemStack(Items.STONE, 4));
            ItemStack renamedStone = new ItemStack(Items.STONE, 4);
            renamedStone.set(DataComponents.CUSTOM_NAME, Component.literal("not-default-material"));
            bot.player().getInventory().setItem(9, renamedStone);
            refreshInventoryMenu(bot);
            BlueprintPlaceableItemEvidence declared = declared(3L, List.of(
                    cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT),
                    cell(new BlueprintOffset(1, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.TEMPORARY))));

            ConstructionMaterialAvailability shortage =
                    MinecraftConstructionMaterialAvailabilitySampler.sample(bot.player(), declared);
            P2GameTestSupport.require(
                    shortage.status() == ConstructionMaterialAvailability.Status.SHORTAGE,
                    "Equipment or changed stacks were incorrectly counted as default construction material");
            requireFinding(shortage, STONE, 2, 1, 1);

            bot.player().inventoryMenu.setCarried(new ItemStack(Items.DIRT));
            bot.player().inventoryMenu.broadcastChanges();
            ConstructionMaterialAvailability unavailable =
                    MinecraftConstructionMaterialAvailabilitySampler.sample(bot.player(), declared);
            P2GameTestSupport.require(
                    unavailable.status() == ConstructionMaterialAvailability.Status.UNAVAILABLE_MENU
                            && unavailable.inventoryMenuFence().isEmpty()
                            && unavailable.findings().isEmpty()
                            && bot.player().inventoryMenu.getCarried().is(Items.DIRT),
                    "A non-empty cursor was incorrectly converted into a zero-stock material result");
            bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
            bot.player().inventoryMenu.broadcastChanges();
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
            timeoutTicks = TIMEOUT_TICKS)
    public static void rejectsAnOpenNativeChestMenuAsUnavailable(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture = P5GameTestSupport.isolatedFixture(
                helper, "construction_material_open_chest_menu");
        TestBot bot = fixture.spawn("bot");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        cleanup.add(() -> {
            if (bot.player().containerMenu != bot.player().inventoryMenu) {
                bot.player().closeContainer();
            }
        });
        try {
            prepareEmptyInventory(bot, 0);
            bot.player().getInventory().setItem(0, new ItemStack(Items.STONE, 2));
            refreshInventoryMenu(bot);
            BlueprintPlaceableItemEvidence declared = declared(4L, List.of(
                    cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT),
                    cell(new BlueprintOffset(1, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.TEMPORARY))));

            P2GameTestSupport.require(bot.player().openMenu(new SimpleMenuProvider(
                            (containerId, playerInventory, player) -> ChestMenu.threeRows(
                                    containerId, playerInventory, new SimpleContainer(27)),
                            Component.literal("P5D-A7 native menu fence"))).isPresent()
                            && bot.player().containerMenu.getClass() == ChestMenu.class,
                    "Material availability fixture did not open a native ChestMenu");
            ConstructionMaterialAvailability unavailable =
                    MinecraftConstructionMaterialAvailabilitySampler.sample(bot.player(), declared);
            P2GameTestSupport.require(
                    unavailable.status() == ConstructionMaterialAvailability.Status.UNAVAILABLE_MENU
                            && unavailable.inventoryMenuFence().isEmpty()
                            && unavailable.findings().isEmpty(),
                    "An open native ChestMenu was incorrectly sampled as the bot inventory");
            bot.player().closeContainer();
            P2GameTestSupport.require(
                    bot.player().containerMenu == bot.player().inventoryMenu
                            && bot.player().inventoryMenu.getCarried().isEmpty(),
                    "Material availability fixture did not restore the native InventoryMenu");
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
            timeoutTicks = TIMEOUT_TICKS)
    public static void rejectsInvalidRegistryAndOffThreadBeforeInventoryRead(
            GameTestHelper helper) {
        P2GameTestSupport.prepareEmptyFloor(helper);
        P5GameTestSupport.IsolatedFixture fixture = P5GameTestSupport.isolatedFixture(
                helper, "construction_material_registry_thread");
        TestBot bot = fixture.spawn("bot");
        P2GameTestSupport.Cleanup cleanup = fixture.cleanup();
        try {
            prepareEmptyInventory(bot, 0);
            bot.player().getInventory().setItem(0, new ItemStack(Items.STICK, 64));
            refreshInventoryMenu(bot);
            BlueprintPlaceableItemEvidence invalidDeclared = new BlueprintPlaceableItemEvidence(
                    new Blueprint(BLUEPRINT_ID, Blueprint.CURRENT_SCHEMA_VERSION, 4L, List.of(
                            cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                                    BlueprintMaterialClass.PERMANENT))),
                    List.of(new PlaceableItemEvidence(STONE_STATE, STICK)));
            BlueprintPlaceableItemEvidence validDeclared = declared(5L, List.of(
                    cell(new BlueprintOffset(0, 0, 0), STONE_STATE,
                            BlueprintMaterialClass.PERMANENT))));
            InventoryMenuSnapshot beforeInventory = MinecraftActionSnapshot.inventoryMenu(bot.player());

            ConstructionMaterialAvailability invalid =
                    MinecraftConstructionMaterialAvailabilitySampler.sample(bot.player(), invalidDeclared);
            Throwable failure = captureFailure(() -> CompletableFuture.runAsync(() ->
                    MinecraftConstructionMaterialAvailabilitySampler.sample(
                            bot.player(), validDeclared)).join());

            P2GameTestSupport.require(
                    invalid.status() == ConstructionMaterialAvailability.Status.UNAVAILABLE_REGISTRY
                            && invalid.inventoryMenuFence().isEmpty()
                            && invalid.findings().isEmpty()
                            && beforeInventory.equals(MinecraftActionSnapshot.inventoryMenu(bot.player()))
                            && failure instanceof CompletionException
                            && failure.getCause() instanceof IllegalStateException,
                    "Invalid registry evidence or an off-thread request reached a material inventory read");
            cleanup.run();
            helper.succeed();
        } catch (RuntimeException | AssertionError exception) {
            cleanup.run();
            throw exception;
        }
    }

    private static BlueprintPlaceableItemEvidence declared(
            long revision, List<BlueprintCell> cells) {
        boolean containsStairs = cells.stream().anyMatch(
                cell -> cell.expectedState().equals(OAK_STAIRS_NORTH));
        List<PlaceableItemEvidence> entries = containsStairs
                ? List.of(
                        new PlaceableItemEvidence(STONE_STATE, STONE),
                        new PlaceableItemEvidence(OAK_STAIRS_NORTH, OAK_STAIRS))
                : List.of(new PlaceableItemEvidence(STONE_STATE, STONE));
        return new BlueprintPlaceableItemEvidence(new Blueprint(BLUEPRINT_ID,
                Blueprint.CURRENT_SCHEMA_VERSION, revision, cells), entries);
    }

    private static BlueprintCell cell(
            BlueprintOffset offset,
            BlockStateFingerprint state,
            BlueprintMaterialClass materialClass) {
        return new BlueprintCell(offset, state, BlueprintPlacementRole.FOUNDATION,
                BlueprintReplacePolicy.PRESERVE_EXISTING, materialClass);
    }

    private static void prepareEmptyInventory(TestBot bot, int selectedHotbar) {
        bot.player().getInventory().clearContent();
        bot.player().getInventory().selected = selectedHotbar;
        bot.player().inventoryMenu.setCarried(ItemStack.EMPTY);
    }

    private static void refreshInventoryMenu(TestBot bot) {
        bot.player().inventoryMenu.broadcastChanges();
    }

    private static void requireFinding(
            ConstructionMaterialAvailability availability,
            ResourceId itemId,
            int requiredCount,
            int availableCount,
            int shortageCount) {
        P2GameTestSupport.require(availability.findings().contains(
                        new ConstructionMaterialAvailability.Finding(itemId, requiredCount,
                                availableCount, shortageCount)),
                "Material availability did not contain the expected canonical item finding for " + itemId);
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            return throwable;
        }
        throw new IllegalStateException("Expected material availability sampling to fail closed");
    }
}
