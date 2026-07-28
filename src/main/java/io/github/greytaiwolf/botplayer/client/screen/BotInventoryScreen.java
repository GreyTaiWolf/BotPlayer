package io.github.greytaiwolf.botplayer.client.screen;

import io.github.greytaiwolf.botplayer.inventory.BotInventoryLayout;
import io.github.greytaiwolf.botplayer.inventory.BotInventoryMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public final class BotInventoryScreen
        extends AbstractContainerScreen<BotInventoryMenu> {
    private static final int PANEL_COLOR = 0xE01C1C1C;
    private static final int BORDER_COLOR = 0xFF8B8B8B;
    private static final int SLOT_COLOR = 0xFF373737;

    public BotInventoryScreen(
            BotInventoryMenu menu, Inventory viewerInventory, Component title) {
        super(menu, viewerInventory, title);
        imageWidth = 176;
        imageHeight = 222;
        inventoryLabelY = BotInventoryLayout.VIEWER_MAIN_Y - 12;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    @Override
    protected void renderBg(
            GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.fill(left, top, left + imageWidth, top + imageHeight, PANEL_COLOR);
        graphics.renderOutline(left, top, imageWidth, imageHeight, BORDER_COLOR);

        menu.slots.forEach(slot -> {
            int x = left + slot.x - 1;
            int y = top + slot.y - 1;
            graphics.fill(x, y, x + 18, y + 18, SLOT_COLOR);
        });
    }
}
