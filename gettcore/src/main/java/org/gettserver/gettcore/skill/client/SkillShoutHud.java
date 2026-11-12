package org.gettserver.gettcore.skill.client;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.client.TextScale;

/** GUI layer: the latest boss skill-name shout, big and white, 30% of the screen above the middle, fading out. */
public final class SkillShoutHud {
    public static final ResourceLocation LAYER_ID = GettCore.id("skill_shout");
    private static final float SCALE = 3.0f;
    /** Below this the font would treat the colour as opaque (alpha bits 0..3). */
    private static final int MIN_ALPHA = 4;

    private static Component text;
    private static long shownAt;

    private SkillShoutHud() {}

    /** A new shout replaces the current one. */
    public static void show(String shout) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        text = Component.literal(shout);
        shownAt = mc.level.getGameTime();
    }

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (text == null || mc.level == null || mc.options.hideGui) return;
        long age = mc.level.getGameTime() - shownAt;
        if (age < 0 || age >= ShoutFade.LIFE_TICKS) {
            text = null;
            return;
        }
        float alpha = ShoutFade.alpha((int) age, delta.getGameTimeDeltaPartialTick(true));
        int a = Mth.floor(alpha * 255);
        if (a < MIN_ALPHA) return;
        Font font = mc.font;
        float scale = TextScale.snap(SCALE, mc.getWindow().getGuiScale());
        float y = g.guiHeight() / 2.0f - g.guiHeight() * 0.3f;
        g.pose().pushPose();
        g.pose().translate(g.guiWidth() / 2.0f, y, 0);
        g.pose().scale(scale, scale, 1);
        g.drawString(font, text, -font.width(text) / 2, -font.lineHeight / 2, (a << 24) | 0xFFFFFF, true);
        g.pose().popPose();
    }
}
