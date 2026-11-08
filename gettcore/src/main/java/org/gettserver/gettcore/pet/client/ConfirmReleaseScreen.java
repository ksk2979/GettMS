package org.gettserver.gettcore.pet.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.PetDexActionPayload;
import org.gettserver.gettcore.pet.CapturedMobItem;
import org.gettserver.gettcore.pet.PetKey;

/**
 * "Release this pet?" confirmation opened by right-clicking a dex cell. Yes sends a RELEASE action; yes, no and Esc
 * all return to the dex. The server re-checks the index (the key is not sent; a stale index is just ignored).
 * Client only.
 */
public class ConfirmReleaseScreen extends Screen {
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 78;
    private static final int BG = 0xE0101018;
    private static final int BORDER = 0xFF8080A0;

    private final Screen parent;
    private final int index;
    private final Component name;

    public ConfirmReleaseScreen(Screen parent, int index, PetKey key) {
        super(Component.translatable("gettcore.gui.pet_dex.release_confirm", CapturedMobItem.displayName(key)));
        this.parent = parent;
        this.index = index;
        this.name = CapturedMobItem.displayName(key);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return Math.max(4, (height - PANEL_H) / 2);
    }

    @Override
    protected void init() {
        int y = top() + PANEL_H - 28;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.yes"), b -> {
            PacketDistributor.sendToServer(new PetDexActionPayload(index, PetDexActionPayload.Action.RELEASE));
            onClose();
        }).bounds(width / 2 - 84, y, 80, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.no"), b -> onClose())
                .bounds(width / 2 + 4, y, 80, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x1 = left(), y1 = top(), x2 = x1 + PANEL_W, y2 = y1 + PANEL_H;
        g.fill(x1, y1, x2, y2, BG);
        PetDexScreen.outline(g, x1, y1, x2, y2, BORDER);
        g.drawCenteredString(font, Component.translatable("gettcore.gui.pet_dex.release_confirm", name),
                width / 2, y1 + 10, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("gettcore.gui.pet_dex.release_note"),
                width / 2, y1 + 26, 0xFFFF7777);
    }
}
