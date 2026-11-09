package org.gettserver.gettcore.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** Client-only text helpers shared by the reinforce and enchant screens. */
public final class GuiText {
    /** Scale of the big info lines (level, effect, odds). */
    public static final float BIG = 1.25f;
    /** Scale of the small cost lines (stones, gold). */
    public static final float SMALL = 0.75f;

    private GuiText() {}

    private static double guiScale() {
        return Minecraft.getInstance().getWindow().getGuiScale();
    }

    /** Left-aligned big line at (x, y), shrunk to fit {@code maxWidth}; scale snapped to whole screen pixels. */
    public static void big(GuiGraphics g, Font font, Component text, int x, int y, int maxWidth, int color) {
        double gs = guiScale();
        float scale = TextScale.snap(BIG, gs);
        float width = Math.max(1, font.width(text));
        if (width * scale > maxWidth) scale = Math.min(scale, TextScale.snapDown(maxWidth / width, gs));
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
    }

    /** Small line whose right edge is at {@code right}; an optional item icon (half size) sits just left of it. */
    public static void smallRight(GuiGraphics g, Font font, ItemStack icon, Component text, int right, int y, int color) {
        float small = TextScale.snap(SMALL, guiScale());
        float w = font.width(text) * small;
        float x = right - w;
        g.pose().pushPose();
        g.pose().translate(x, y, 0);
        g.pose().scale(small, small, 1);
        g.drawString(font, text, 0, 0, color, false);
        g.pose().popPose();
        if (!icon.isEmpty()) {
            g.pose().pushPose();
            g.pose().translate(x - 10, y - 2, 0);
            g.pose().scale(0.5f, 0.5f, 1);
            g.renderItem(icon, 0, 0);
            g.pose().popPose();
        }
    }
}
