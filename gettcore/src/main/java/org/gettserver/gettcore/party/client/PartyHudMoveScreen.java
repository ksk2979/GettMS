package org.gettserver.gettcore.party.client;

import java.util.List;
import net.minecraft.Util;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.gettserver.gettcore.network.PartySyncPayload;
import org.gettserver.gettcore.quest.client.HudPosition;
import org.lwjgl.glfw.GLFW;

/**
 * Move the party member health bars: drag the box or nudge it with the arrow keys. 저장 keeps the position, 초기화
 * puts it back to the default (not saved yet), 취소 / Esc leaves it as it was. Client only.
 */
public final class PartyHudMoveScreen extends Screen {
    private static final int BUTTON_W = 70;

    private final Screen parent;
    private HudPosition pos;
    private boolean dragging;
    private double dragMouseX;
    private double dragMouseY;
    private int dragLeft;
    private int dragTop;

    public PartyHudMoveScreen(Screen parent) {
        super(Component.translatable("gettcore.party.hud.move_title"));
        this.parent = parent;
        this.pos = ClientParty.position();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** The real party, or a sample when there is none, so there is always a box to drag. */
    private PartySyncPayload shown() {
        PartySyncPayload party = ClientParty.party();
        if (party.inParty()) return party;
        return new PartySyncPayload(Component.translatable("gettcore.party.title").getString(), List.of(
                new PartySyncPayload.Entry(Util.NIL_UUID, "Player1", true, 20, 20),
                new PartySyncPayload.Entry(Util.NIL_UUID, "Player2", true, 12, 20)));
    }

    private int boxH() {
        return PartyHud.height(shown().members().size(), ClientParty.folded());
    }

    private int boxLeft() {
        return pos.left(width, PartyHud.WIDTH);
    }

    private int boxTop() {
        return pos.top(height, boxH());
    }

    private boolean overBox(double mx, double my) {
        int l = boxLeft();
        int t = boxTop();
        return mx >= l && mx < l + PartyHud.WIDTH && my >= t && my < t + boxH();
    }

    private void moveTo(int left, int top) {
        pos = HudPosition.fromAbsolute(left, top, PartyHud.WIDTH, boxH(), width, height);
    }

    @Override
    protected void init() {
        int total = 3 * BUTTON_W + 8;
        int x = (width - total) / 2;
        int y = height - 28;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.hud.move_save"), b -> {
            ClientParty.savePosition(pos);
            onClose();
        }).bounds(x, y, BUTTON_W, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.hud.move_reset"),
                b -> pos = ClientParty.DEFAULT_POSITION).bounds(x + BUTTON_W + 4, y, BUTTON_W, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.hud.move_cancel"), b -> onClose())
                .bounds(x + 2 * (BUTTON_W + 4), y, BUTTON_W, 20).build());
    }

    @Override
    public void onClose() {
        if (minecraft != null) minecraft.setScreen(parent);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0x60000000);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("gettcore.quest.hud.move_hint"), width / 2, 20, 0xFFAAAAAA);
        int l = boxLeft();
        int t = boxTop();
        PartyHud.draw(g, shown(), l, t, ClientParty.folded());
        if (dragging || overBox(mouseX, mouseY)) g.renderOutline(l - 1, t - 1, PartyHud.WIDTH + 2, boxH() + 2, 0xFFFFFF55);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        if (button == GLFW.GLFW_MOUSE_BUTTON_LEFT && overBox(mouseX, mouseY)) {
            dragging = true;
            dragMouseX = mouseX;
            dragMouseY = mouseY;
            dragLeft = boxLeft();
            dragTop = boxTop();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            moveTo(dragLeft + (int) Math.round(mouseX - dragMouseX), dragTop + (int) Math.round(mouseY - dragMouseY));
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (dragging && button == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            dragging = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int dx = 0;
        int dy = 0;
        switch (keyCode) {
            case GLFW.GLFW_KEY_LEFT -> dx = -1;
            case GLFW.GLFW_KEY_RIGHT -> dx = 1;
            case GLFW.GLFW_KEY_UP -> dy = -1;
            case GLFW.GLFW_KEY_DOWN -> dy = 1;
            default -> {
                return super.keyPressed(keyCode, scanCode, modifiers);
            }
        }
        if (!dragging) moveTo(boxLeft() + dx, boxTop() + dy);
        return true;
    }
}
