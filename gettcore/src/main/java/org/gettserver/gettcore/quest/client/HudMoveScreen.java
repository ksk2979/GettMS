package org.gettserver.gettcore.quest.client;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/**
 * Ctrl+-: move the quest tracker HUD. The box is drawn where it sits (real quest, or a sample when there is none);
 * drag it or nudge it with the arrow keys. 저장 writes the position and closes, 초기화 puts it back to the default
 * (not saved yet), 취소/Esc closes without saving. Nothing changes the live position until 저장. Client only.
 */
public final class HudMoveScreen extends Screen {
    private static final int DIM = 0x60000000;
    private static final int OUTLINE = 0xFFFFFF55;
    private static final int BUTTON_W = 70;
    private static final int BUTTON_H = 20;
    private static final int GAP = 4;

    private HudPosition pos;
    private QuestHud.Layout box;
    private boolean dragging;
    private double dragMouseX;
    private double dragMouseY;
    private int dragLeft;
    private int dragTop;

    public HudMoveScreen() {
        super(Component.translatable("gettcore.quest.hud.move_title"));
        this.pos = ClientQuestState.hudPosition();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        box = QuestHud.layout(font, QuestHud.linesOrSample());
        int total = 3 * BUTTON_W + 2 * GAP;
        int x = (width - total) / 2;
        int y = height - BUTTON_H - 8;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.hud.move_save"), b -> {
            ClientQuestState.saveHudPosition(pos);
            minecraft.setScreen(null);
        }).bounds(x, y, BUTTON_W, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.hud.move_reset"),
                b -> pos = HudPosition.DEFAULT).bounds(x + BUTTON_W + GAP, y, BUTTON_W, BUTTON_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.quest.hud.move_cancel"), b -> onClose())
                .bounds(x + 2 * (BUTTON_W + GAP), y, BUTTON_W, BUTTON_H).build());
    }

    @Override
    public void tick() {
        // Keep the real lines in step with sync updates arriving while the screen is open.
        if (!dragging) box = QuestHud.layout(font, QuestHud.linesOrSample());
    }

    private int boxLeft() {
        return pos.left(width, box.width());
    }

    private int boxTop() {
        return pos.top(height, box.height());
    }

    private boolean overBox(double mx, double my) {
        int l = boxLeft();
        int t = boxTop();
        return mx >= l && mx < l + box.width() && my >= t && my < t + box.height();
    }

    private void moveTo(int left, int top) {
        pos = HudPosition.fromAbsolute(left, top, box.width(), box.height(), width, height);
    }

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Light dim (no blur) so other HUDs such as the minimap stay visible for lining up.
        g.fill(0, 0, width, height, DIM);
        g.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        g.drawCenteredString(font, Component.translatable("gettcore.quest.hud.move_hint"), width / 2, 20, 0xFFAAAAAA);
        int l = boxLeft();
        int t = boxTop();
        QuestHud.draw(g, box, l, t);
        if (dragging || overBox(mouseX, mouseY)) g.renderOutline(l - 1, t - 1, box.width() + 2, box.height() + 2, OUTLINE);
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
