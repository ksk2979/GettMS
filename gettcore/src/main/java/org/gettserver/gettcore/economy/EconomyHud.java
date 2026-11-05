package org.gettserver.gettcore.economy;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.gettserver.gettcore.GettCore;

@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class EconomyHud {
    private static final int ICON = 9;

    private EconomyHud() {}

    public static String formatGold(long amount) {
        return Gold.format(amount);
    }

    @SubscribeEvent
    public static void onRender(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof InventoryScreen s)) return;
        Font font = Minecraft.getInstance().font;
        String text = formatGold(ClientBalance.get());
        int width = font.width(text) + ICON + 2;
        int x = s.getGuiLeft() + s.getXSize() - 4 - width + ClientConfig.hudOffsetX();
        int y = s.getGuiTop() + 6 + ClientConfig.hudOffsetY();
        var g = event.getGuiGraphics();
        g.blit(GettCore.id("textures/gui/coin.png"), x, y, 0, 0, ICON, ICON, ICON, ICON);
        g.drawString(font, text, x + ICON + 2, y + 1, 0xFFFFFF, true);
    }
}
