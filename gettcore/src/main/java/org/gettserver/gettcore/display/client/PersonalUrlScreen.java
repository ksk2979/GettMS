package org.gettserver.gettcore.display.client;

import java.util.UUID;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.display.DisplayRules;
import org.gettserver.gettcore.network.DisplayActionPayload;

/** Asks for the address of the video only this player will see on a display. */
public final class PersonalUrlScreen extends Screen {
    private final UUID display;
    private EditBox url;

    public PersonalUrlScreen(UUID display) {
        super(Component.translatable("gettcore.display.personal.title"));
        this.display = display;
    }

    @Override
    protected void init() {
        int w = Math.min(320, width - 40);
        int x = (width - w) / 2;
        int y = height / 2 - 10;
        url = new EditBox(font, x, y, w, 20, Component.translatable("gettcore.display.personal.hint"));
        url.setMaxLength(DisplayRules.MAX_URL_LENGTH);
        url.setHint(Component.translatable("gettcore.display.personal.hint"));
        addRenderableWidget(url);
        setInitialFocus(url);
        addRenderableWidget(Button.builder(Component.translatable("gettcore.display.personal.play"), b -> submit())
                .bounds(x, y + 28, w / 2 - 2, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"), b -> onClose())
                .bounds(x + w / 2 + 2, y + 28, w / 2 - 2, 20).build());
    }

    private void submit() {
        String value = DisplayRules.cleanUrl(url.getValue());
        if (value.isEmpty()) return;
        PacketDistributor.sendToServer(new DisplayActionPayload(display, DisplayRules.ACTION_PERSONAL, value));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257 || keyCode == 335) { // Enter, keypad Enter
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        g.drawCenteredString(font, title, width / 2, height / 2 - 30, 0xFFFFFF);
    }
}
