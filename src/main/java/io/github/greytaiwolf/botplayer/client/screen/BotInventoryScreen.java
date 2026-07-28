package io.github.greytaiwolf.botplayer.client.screen;

import io.github.greytaiwolf.botplayer.inventory.BotInventoryLayout;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Inventory;

public final class BotInventoryScreen
        extends AbstractContainerScreen<BotInventoryMenu> {
    private static final ResourceLocation INVENTORY_TEXTURE =
            ResourceLocation.withDefaultNamespace(
                    "textures/gui/container/inventory.png");
    private static final ResourceLocation GENERIC_54_TEXTURE =
            ResourceLocation.withDefaultNamespace(
                    "textures/gui/container/generic_54.png");
    private static final ResourceLocation HOTBAR_SELECTION_SPRITE =
            ResourceLocation.withDefaultNamespace("hud/hotbar_selection");
    private static final int BOT_INVENTORY_TEXTURE_HEIGHT = 166;
    private static final int VIEWER_SLICE_Y = 160;
    private static final int VIEWER_TEXTURE_Y = 126;
    private static final int VIEWER_SLICE_HEIGHT = 96;
    private static final int CRAFTING_MASK_X = 96;
    private static final int CRAFTING_MASK_Y = 7;
    private static final int CRAFTING_MASK_WIDTH = 75;
    private static final int CRAFTING_MASK_HEIGHT = 74;
    private static final int BACKGROUND_SAMPLE_X = 95;
    private static final int BACKGROUND_SAMPLE_Y = 7;
    private static final int HOTBAR_SELECTION_WIDTH = 24;
    private static final int HOTBAR_SELECTION_HEIGHT = 23;

    public BotInventoryScreen(
            BotInventoryMenu menu, Inventory viewerInventory, Component title) {
        super(menu, viewerInventory, title);
        imageWidth = BotInventoryLayout.IMAGE_WIDTH;
        imageHeight = BotInventoryLayout.IMAGE_HEIGHT;
        titleLabelX = 97;
        titleLabelY = 6;
        inventoryLabelX = 8;
        inventoryLabelY = 162;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(
            GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.blit(
                INVENTORY_TEXTURE,
                left,
                top,
                0,
                0,
                BotInventoryLayout.IMAGE_WIDTH,
                BOT_INVENTORY_TEXTURE_HEIGHT);
        graphics.blit(
                INVENTORY_TEXTURE,
                left + CRAFTING_MASK_X,
                top + CRAFTING_MASK_Y,
                CRAFTING_MASK_WIDTH,
                CRAFTING_MASK_HEIGHT,
                BACKGROUND_SAMPLE_X,
                BACKGROUND_SAMPLE_Y,
                1,
                1,
                256,
                256);
        graphics.blit(
                GENERIC_54_TEXTURE,
                left,
                top + VIEWER_SLICE_Y,
                0,
                VIEWER_TEXTURE_Y,
                BotInventoryLayout.IMAGE_WIDTH,
                VIEWER_SLICE_HEIGHT);

        int selected = menu.selectedBotHotbar();
        graphics.blitSprite(
                HOTBAR_SELECTION_SPRITE,
                left
                        + BotInventoryLayout.BOT_HOTBAR_X
                        + selected * BotInventoryLayout.SLOT_SPACING
                        - 4,
                top + BotInventoryLayout.BOT_HOTBAR_Y - 4,
                HOTBAR_SELECTION_WIDTH,
                HOTBAR_SELECTION_HEIGHT);

        if (minecraft != null && minecraft.level != null) {
            Entity entity = minecraft.level.getEntity(menu.botEntityId());
            if (entity instanceof LivingEntity livingEntity) {
                InventoryScreen.renderEntityInInventoryFollowsMouse(
                        graphics,
                        left + 26,
                        top + 8,
                        left + 75,
                        top + 78,
                        30,
                        0.0625F,
                        mouseX,
                        mouseY,
                        livingEntity);
            }
        }
    }
}
