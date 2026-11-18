package org.gettserver.gettcore.nick.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.NicknameSetPayload;
import org.gettserver.gettcore.nick.NicknameBook;
import org.lwjgl.glfw.GLFW;

/**
 * The box opened by a nickname ticket: type the new nickname, 확인 / Enter sends it. The server answers in chat and
 * takes the ticket only when the nickname was accepted. Client only.
 */
public final class NicknameScreen extends Screen {
    private static final int W = 210;
    private static final int H = 108;
    private static final int BG = 0xF00E1622;
    private static final int BORDER = 0xFF3A4A68;
    private static final int BAND = 0xFF1B2C48;

    private EditBox box;
    private Button confirm;

    public NicknameScreen() {
        super(Component.translatable("gettcore.nickname.title"));
    }

    public static void open() {
        Minecraft.getInstance().setScreen(new NicknameScreen());
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
        box = new EditBox(font, left() + 12, top() + 50, W - 24, 18, title);
        box.setMaxLength(NicknameBook.MAX);
        box.setValue(old);
        box.setResponder(s -> confirm.active = NicknameBook.clean(s) != null);
        addRenderableWidget(box);
        confirm = addRenderableWidget(Button.builder(Component.translatable("gettcore.party.name.ok"), b -> submit())
                .bounds(left() + 12, top() + H - 30, 88, 20).build());
        confirm.active = NicknameBook.clean(old) != null;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.party.name.cancel"), b -> onClose())
                .bounds(left() + W - 12 - 88, top() + H - 30, 88, 20).build());
        setInitialFocus(box);
    }

    private void submit() {
        String name = NicknameBook.clean(box.getValue());
        if (name == null) return;
        PacketDistributor.sendToServer(new NicknameSetPayload(name));
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
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x = left();
        int y = top();
        g.fill(x, y, x + W, y + H, BG);
        g.renderOutline(x, y, W, H, BORDER);
        g.fill(x + 6, y + 6, x + W - 6, y + 22, BAND);
        g.drawCenteredString(font, title, x + W / 2, y + 10, 0xFFFFFFFF);
        g.drawString(font, Component.translatable("gettcore.nickname.hint1", NicknameBook.MIN, NicknameBook.MAX),
                x + 12, y + 27, 0xFFC8D0DC, false);
        g.drawString(font, Component.translatable("gettcore.nickname.hint2"), x + 12, y + 38, 0xFF8090A8, false);
    }
}
