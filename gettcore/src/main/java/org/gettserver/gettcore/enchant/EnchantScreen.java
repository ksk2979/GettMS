package org.gettserver.gettcore.enchant;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import org.gettserver.gettcore.shop.ShopMenu;

/** Client-only. A plain container look for now; Task 4 adds the real screen and the result effect. */
public class EnchantScreen extends AbstractContainerScreen<EnchantMenu> {
    public EnchantScreen(EnchantMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = 360;
        imageHeight = 200;
        inventoryLabelX = ShopMenu.INV_X;
        inventoryLabelY = ShopMenu.INV_Y - 11;
    }

    /** Called by the {@code EnchantResultPayload} handler. No-op until Task 4. */
    public static void onResult(String abilityId, int level) {}

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xFF2B2B2B);
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
            g.fill(x, y, x + 16, y + 16, 0xFF373737);
        }
    }
}
