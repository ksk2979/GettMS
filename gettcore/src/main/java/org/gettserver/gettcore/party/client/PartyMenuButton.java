package org.gettserver.gettcore.party.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.neoforged.neoforge.client.event.ScreenEvent;

/**
 * A small party button in the game menu (Esc), left of the 설정 row, the way Create adds its own: no key binding
 * needed. Opens {@link PartyScreen}. Not added when the menu has no buttons (F3 + Esc). Client only.
 */
public final class PartyMenuButton {
    private static final int SIZE = 20;
    private static final int GAP = 4;
    /** Two people, 14 x 9. */
    private static final String[] ICON = {
            "...##.........",
            "..####....##..",
            "..####...####.",
            "...##....####.",
            ".######...##..",
            "########.####.",
            "########.#####",
            "########.#####",
            "########.#####",
    };

    private PartyMenuButton() {}

    public static void onInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof PauseScreen screen)) return;
        Button anchor = null;
        for (GuiEventListener l : event.getListenersList()) {
            if (l instanceof Button b && b.getMessage().getContents() instanceof TranslatableContents tc
                    && tc.getKey().equals("menu.options")) {
                anchor = b;
                break;
            }
        }
        if (anchor == null) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        Button button = new IconButton(anchor.getX() - GAP - SIZE, anchor.getY(),
                b -> mc.setScreen(new PartyScreen()));
        button.setTooltip(Tooltip.create(Component.translatable("gettcore.party.title")));
        event.addListener(button);
    }

    private static final class IconButton extends Button {
        IconButton(int x, int y, OnPress onPress) {
            super(x, y, SIZE, SIZE, Component.empty(), onPress, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            super.renderWidget(g, mouseX, mouseY, partialTick);
            int ox = getX() + (SIZE - ICON[0].length()) / 2;
            int oy = getY() + (SIZE - ICON.length) / 2;
            draw(g, ox + 1, oy + 1, 0xFF303030);
            draw(g, ox, oy, isHoveredOrFocused() ? 0xFFFFFFA0 : 0xFFFFFFFF);
        }

        private static void draw(GuiGraphics g, int ox, int oy, int color) {
            for (int r = 0; r < ICON.length; r++) {
                String row = ICON[r];
                for (int c = 0; c < row.length(); c++) {
                    if (row.charAt(c) == '#') g.fill(ox + c, oy + r, ox + c + 1, oy + r + 1, color);
                }
            }
        }
    }
}
