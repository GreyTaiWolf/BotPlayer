package io.github.greytaiwolf.botplayer.inventory;

import com.mojang.datafixers.util.Pair;
import io.github.greytaiwolf.botplayer.kernel.BotServerPlayer;
import javax.annotation.Nullable;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentEffectComponents;
import net.minecraft.world.item.enchantment.EnchantmentHelper;

/**
 * A 77-slot view over the authoritative bot and viewer inventories.
 *
 * <p>The client receives a 41-slot placeholder container. Vanilla menu synchronization fills it
 * from the server-side {@link BotServerPlayer} inventory; no inventory content is copied into a
 * second server container.
 */
public final class BotInventoryMenu extends AbstractContainerMenu {
    private static final EquipmentSlot[] ARMOR_SLOTS = {
        EquipmentSlot.HEAD,
        EquipmentSlot.CHEST,
        EquipmentSlot.LEGS,
        EquipmentSlot.FEET
    };
    private static final ResourceLocation[] ARMOR_ICONS = {
        InventoryMenu.EMPTY_ARMOR_SLOT_HELMET,
        InventoryMenu.EMPTY_ARMOR_SLOT_CHESTPLATE,
        InventoryMenu.EMPTY_ARMOR_SLOT_LEGGINGS,
        InventoryMenu.EMPTY_ARMOR_SLOT_BOOTS
    };

    @Nullable private final InventorySessionToken sessionToken;
    @Nullable private final BotInventorySessionManager sessionManager;
    private final int botEntityId;
    private final DataSlot selectedBotHotbar;

    public BotInventoryMenu(
            int containerId,
            Inventory viewerInventory,
            RegistryFriendlyByteBuf extraData) {
        this(
                containerId,
                viewerInventory,
                new SimpleContainer(BotInventoryLayout.BOT_INVENTORY_SIZE),
                viewerInventory.player,
                null,
                null,
                extraData.readVarInt(),
                DataSlot.standalone());
    }

    public BotInventoryMenu(
            int containerId,
            Inventory viewerInventory,
            BotServerPlayer bot,
            InventorySessionToken sessionToken,
            BotInventorySessionManager sessionManager) {
        this(
                containerId,
                viewerInventory,
                bot.getInventory(),
                bot,
                sessionToken,
                sessionManager,
                bot.getId(),
                selectedHotbarData(bot.getInventory()));
    }

    private BotInventoryMenu(
            int containerId,
            Inventory viewerInventory,
            Container botInventory,
            LivingEntity equipmentOwner,
            @Nullable InventorySessionToken sessionToken,
            @Nullable BotInventorySessionManager sessionManager,
            int botEntityId,
            DataSlot selectedBotHotbar) {
        super(BotPlayerMenus.BOT_INVENTORY.get(), containerId);
        checkContainerSize(botInventory, BotInventoryLayout.BOT_INVENTORY_SIZE);
        this.sessionToken = sessionToken;
        this.sessionManager = sessionManager;
        this.botEntityId = botEntityId;
        this.selectedBotHotbar = addDataSlot(selectedBotHotbar);

        for (int armorIndex = 0; armorIndex < ARMOR_SLOTS.length; armorIndex++) {
            EquipmentSlot equipmentSlot = ARMOR_SLOTS[armorIndex];
            ResourceLocation emptyIcon = ARMOR_ICONS[armorIndex];
            addSlot(new BotEquipmentSlot(
                    botInventory,
                    equipmentOwner,
                    equipmentSlot,
                    BotInventoryLayout.botInventoryIndex(armorIndex),
                    BotInventoryLayout.BOT_EQUIPMENT_X,
                    BotInventoryLayout.BOT_EQUIPMENT_Y
                            + armorIndex * BotInventoryLayout.SLOT_SPACING,
                    emptyIcon));
        }

        addSlot(new BotOffhandSlot(
                botInventory,
                equipmentOwner,
                BotInventoryLayout.botInventoryIndex(BotInventoryLayout.BOT_OFFHAND_SLOT),
                BotInventoryLayout.BOT_OFFHAND_X,
                BotInventoryLayout.BOT_OFFHAND_Y));

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int menuSlot = BotInventoryLayout.BOT_MAIN_START + row * 9 + column;
                addSlot(new Slot(
                        botInventory,
                        BotInventoryLayout.botInventoryIndex(menuSlot),
                        BotInventoryLayout.BOT_MAIN_X
                                + column * BotInventoryLayout.SLOT_SPACING,
                        BotInventoryLayout.BOT_MAIN_Y
                                + row * BotInventoryLayout.SLOT_SPACING));
            }
        }

        for (int column = 0; column < 9; column++) {
            int menuSlot = BotInventoryLayout.BOT_HOTBAR_START + column;
            addSlot(new Slot(
                    botInventory,
                    BotInventoryLayout.botInventoryIndex(menuSlot),
                    BotInventoryLayout.BOT_HOTBAR_X
                            + column * BotInventoryLayout.SLOT_SPACING,
                    BotInventoryLayout.BOT_HOTBAR_Y));
        }

        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int menuSlot = BotInventoryLayout.VIEWER_MAIN_START + row * 9 + column;
                addSlot(new Slot(
                        viewerInventory,
                        BotInventoryLayout.viewerInventoryIndex(menuSlot),
                        BotInventoryLayout.VIEWER_MAIN_X
                                + column * BotInventoryLayout.SLOT_SPACING,
                        BotInventoryLayout.VIEWER_MAIN_Y
                                + row * BotInventoryLayout.SLOT_SPACING));
            }
        }

        for (int column = 0; column < 9; column++) {
            int menuSlot = BotInventoryLayout.VIEWER_HOTBAR_START + column;
            addSlot(new Slot(
                    viewerInventory,
                    BotInventoryLayout.viewerInventoryIndex(menuSlot),
                    BotInventoryLayout.VIEWER_HOTBAR_X
                            + column * BotInventoryLayout.SLOT_SPACING,
                    BotInventoryLayout.VIEWER_HOTBAR_Y));
        }

        if (slots.size() != BotInventoryLayout.TOTAL_MENU_SLOTS) {
            throw new IllegalStateException("Bot inventory menu layout is incomplete");
        }
    }

    @Override
    public boolean stillValid(Player viewer) {
        if (sessionToken == null || sessionManager == null) {
            return true;
        }
        if (!sessionToken.viewerId().equals(viewer.getUUID())) {
            return false;
        }
        return sessionManager.revalidate(sessionToken).valid();
    }

    public Optional<InventorySessionToken> sessionToken() {
        return Optional.ofNullable(sessionToken);
    }

    public int botEntityId() {
        return botEntityId;
    }

    public int selectedBotHotbar() {
        return clampHotbarSlot(selectedBotHotbar.get());
    }

    @Override
    public void removed(Player viewer) {
        super.removed(viewer);
        if (sessionToken == null || sessionManager == null) {
            return;
        }
        sessionManager.forceClose(sessionToken, InventoryCloseReason.VIEWER_REQUEST);
        sessionManager.confirmClosed(sessionToken);
    }

    @Override
    public ItemStack quickMoveStack(Player viewer, int menuIndex) {
        if (menuIndex < 0 || menuIndex >= slots.size()) {
            return ItemStack.EMPTY;
        }
        Slot source = slots.get(menuIndex);
        if (!source.hasItem() || !source.mayPickup(viewer)) {
            return ItemStack.EMPTY;
        }

        ItemStack moving = source.getItem();
        ItemStack original = moving.copy();
        int countBefore = totalMenuItemCount();
        boolean moved;
        if (menuIndex < BotInventoryLayout.BOT_INVENTORY_SIZE) {
            moved = moveItemStackTo(
                    moving,
                    BotInventoryLayout.VIEWER_MAIN_START,
                    BotInventoryLayout.TOTAL_MENU_SLOTS,
                    false);
        } else {
            moved = tryMoveViewerStackToBotEquipment(moving)
                    || moveItemStackTo(
                            moving,
                            BotInventoryLayout.BOT_MAIN_START,
                            BotInventoryLayout.BOT_INVENTORY_SIZE,
                            false);
        }

        if (!moved) {
            return ItemStack.EMPTY;
        }
        if (moving.isEmpty()) {
            source.setByPlayer(ItemStack.EMPTY, original);
        } else {
            source.setChanged();
        }
        source.onTake(viewer, moving);

        if (totalMenuItemCount() != countBefore) {
            closeForConservationFailure();
            return ItemStack.EMPTY;
        }
        return original;
    }

    private boolean tryMoveViewerStackToBotEquipment(ItemStack moving) {
        for (int slotIndex = 0; slotIndex < ARMOR_SLOTS.length; slotIndex++) {
            Slot target = slots.get(slotIndex);
            if (!target.hasItem() && target.mayPlace(moving)) {
                return moveItemStackTo(moving, slotIndex, slotIndex + 1, false);
            }
        }
        return false;
    }

    private int totalMenuItemCount() {
        int count = 0;
        for (Slot slot : slots) {
            count = Math.addExact(count, slot.getItem().getCount());
        }
        return count;
    }

    private void closeForConservationFailure() {
        if (sessionToken == null || sessionManager == null) {
            return;
        }
        sessionManager.forceClose(
                sessionToken, InventoryCloseReason.ITEM_CONSERVATION_FAILURE);
    }

    private static DataSlot selectedHotbarData(Inventory inventory) {
        return new DataSlot() {
            @Override
            public int get() {
                return clampHotbarSlot(inventory.selected);
            }

            @Override
            public void set(int value) {
                // Server authoritative: client data updates must not select a bot slot.
            }
        };
    }

    private static int clampHotbarSlot(int selected) {
        return Math.max(0, Math.min(8, selected));
    }

    private static final class BotEquipmentSlot extends Slot {
        private final LivingEntity owner;
        private final EquipmentSlot equipmentSlot;
        private final ResourceLocation emptyIcon;

        private BotEquipmentSlot(
                Container container,
                LivingEntity owner,
                EquipmentSlot equipmentSlot,
                int inventoryIndex,
                int x,
                int y,
                ResourceLocation emptyIcon) {
            super(container, inventoryIndex, x, y);
            this.owner = owner;
            this.equipmentSlot = equipmentSlot;
            this.emptyIcon = emptyIcon;
        }

        @Override
        public void setByPlayer(ItemStack carried, ItemStack previous) {
            owner.onEquipItem(equipmentSlot, previous, carried);
            super.setByPlayer(carried, previous);
        }

        @Override
        public int getMaxStackSize() {
            return 1;
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return stack.canEquip(equipmentSlot, owner);
        }

        @Override
        public boolean mayPickup(Player viewer) {
            ItemStack stack = getItem();
            return stack.isEmpty()
                    || viewer.isCreative()
                    || !EnchantmentHelper.has(
                            stack, EnchantmentEffectComponents.PREVENT_ARMOR_CHANGE);
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(InventoryMenu.BLOCK_ATLAS, emptyIcon);
        }
    }

    private static final class BotOffhandSlot extends Slot {
        private final LivingEntity owner;

        private BotOffhandSlot(
                Container container, LivingEntity owner, int index, int x, int y) {
            super(container, index, x, y);
            this.owner = owner;
        }

        @Override
        public void setByPlayer(ItemStack carried, ItemStack previous) {
            owner.onEquipItem(EquipmentSlot.OFFHAND, previous, carried);
            super.setByPlayer(carried, previous);
        }

        @Override
        public Pair<ResourceLocation, ResourceLocation> getNoItemIcon() {
            return Pair.of(
                    InventoryMenu.BLOCK_ATLAS,
                    InventoryMenu.EMPTY_ARMOR_SLOT_SHIELD);
        }
    }
}
