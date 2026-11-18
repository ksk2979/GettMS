package org.gettserver.gettcore.party.client;

import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.PartyActionPayload;
import org.gettserver.gettcore.party.PartyBook;
import org.lwjgl.glfw.GLFW;

/** "Name of the new party" box: 확인 / Enter asks the server to create it, 취소 / Esc goes back. Client only. */
public final class PartyNameScreen extends Screen {
    private static final int W = 200;
    private static final int H = 96;

    private final Screen parent;
    private EditBox box;
    private Button confirm;

    public PartyNameScreen(Screen parent) {
        super(Component.translatable("gettcore.party.name.title"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - W) / 2;
    }

    private int top() {
        return Math.max(4, (height - H) / 2);
    }

    @Override
    protected void init() {
        String old = box == null ? "" : box.getValue();
        box = new EditBox(font, left() + 12, top() + 38, W - 24, 18, Component.translatable("gettcore.party.name.title"));
        box.setMaxLength(PartyBook.NAME_MAX);
        box.setValue(old);
        box.setResponder(s -> confirm.active = PartyBook.cleanName(s) != null);
        addRenderableWidget(box);
        confirm = addRenderableWidget(Button.builder(Component.translatable("gettcore.party.name.ok"), b -> submit())
                .bounds(left() + 12, top() + H - 30, 84, 20).build());
        confirm.active = PartyBook.cleanName(old) != null;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.party.name.cancel"), b -> onClose())
                .bounds(left() + W - 12 - 84, top() + H - 30, 84, 20).build());
        setInitialFocus(box);
    }

    private void submit() {
        String name = PartyBook.cleanName(box.getValue());
        if (name == null) return;
        PacketDistributor.sendToServer(new PartyActionPayload(PartyActionPayload.Action.CREATE, name, Util.NIL_UUID));
        onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            submit();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        PartyScreen.panel(g, font, title, left(), top(), W, H);
        g.drawString(font, Component.translatable("gettcore.party.name.hint", PartyBook.NAME_MAX), left() + 12, top() + 27,
                0xFF8090A8, false);
    }
}
