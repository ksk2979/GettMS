package org.gettserver.gettcore.npc;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.DialogChoicePayload;
import org.gettserver.gettcore.network.OpenDialogPayload;
import org.lwjgl.glfw.GLFW;

public class DialogScreen extends Screen {
    private static final int BOX_BG = 0xC0101018;
    private static final int BOX_BORDER = 0xFF8080A0;
    private static final int PORTRAIT_SIZE = 80;
    private static final int BTN_W = 140;

    private final OpenDialogPayload data;
    private boolean closeSent;

    public DialogScreen(OpenDialogPayload data) {
        super(Component.literal(data.npcName()));
        this.data = data;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int btnH = 20;
        int x = width - BTN_W - 10;
        int count = data.choiceLabels().size();
        int y = height - 10 - count * (btnH + 4) + 4;
        for (int i = 0; i < count; i++) {
            final int idx = i;
            addRenderableWidget(Button.builder(Component.literal((i + 1) + ". " + data.choiceLabels().get(i)),
                    b -> choose(idx)).bounds(x, y + i * (btnH + 4), BTN_W, btnH).build());
        }
    }

    private void choose(int index) {
        if (index < 0 || index >= data.choiceLabels().size()) {
            return;
        }
        PacketDistributor.sendToServer(new DialogChoicePayload(index));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        int idx = -1;
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_9) {
            idx = keyCode - GLFW.GLFW_KEY_1;
        } else if (keyCode >= GLFW.GLFW_KEY_KP_1 && keyCode <= GLFW.GLFW_KEY_KP_9) {
            idx = keyCode - GLFW.GLFW_KEY_KP_1;
        }
        if (idx >= 0) {
            choose(idx);
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void onClose() {
        sendCloseOnce();
        super.onClose();
    }

    @Override
    public void removed() {
        // When replaced by the next node's DialogScreen the server already moved on; notify only on a real close.
        if (!(Minecraft.getInstance().screen instanceof DialogScreen)) {
            sendCloseOnce();
        }
    }

    private void sendCloseOnce() {
        if (!closeSent) {
            closeSent = true;
            PacketDistributor.sendToServer(new DialogChoicePayload(-1));
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // 배경(블러)과 버튼을 먼저 그리고, 그 위에 대화 상자를 그린다. 먼저 그리면 블러에 묻힌다.
        super.render(g, mouseX, mouseY, partialTick);
        int px1 = 10;
        int py2 = height - 10;
        int py1 = py2 - PORTRAIT_SIZE - 14;
        int px2 = px1 + PORTRAIT_SIZE + 8;
        box(g, px1, py1, px2, py2);
        g.drawString(font, data.npcName(), px1 + 4, py1 + 3, 0xFFFFFF55, false);
        int ix1 = px1 + 4;
        int iy1 = py1 + 14;
        if (data.portrait().isPresent()) {
            g.blit(data.portrait().get(), ix1, iy1, 0, 0, PORTRAIT_SIZE, PORTRAIT_SIZE, PORTRAIT_SIZE, PORTRAIT_SIZE);
        } else if (Minecraft.getInstance().level != null
                && Minecraft.getInstance().level.getEntity(data.npcEntityId()) instanceof LivingEntity living) {
            InventoryScreen.renderEntityInInventoryFollowsMouse(g, ix1, iy1, ix1 + PORTRAIT_SIZE, iy1 + PORTRAIT_SIZE,
                    30, 0.0625F, mouseX, mouseY, living);
        }

        int tx1 = px2 + 8;
        int tx2 = width - BTN_W - 20;
        if (tx2 - tx1 > 40) {
            box(g, tx1, py1, tx2, py2);
            List<FormattedCharSequence> lines = font.split(Component.literal(data.text()), tx2 - tx1 - 12);
            int ly = py1 + 6;
            for (FormattedCharSequence line : lines) {
                if (ly + font.lineHeight > py2 - 4) {
                    break;
                }
                g.drawString(font, line, tx1 + 6, ly, 0xFFFFFFFF, false);
                ly += font.lineHeight + 2;
            }
        }
    }

    private static void box(GuiGraphics g, int x1, int y1, int x2, int y2) {
        g.fill(x1, y1, x2, y2, BOX_BG);
        g.fill(x1, y1, x2, y1 + 1, BOX_BORDER);
        g.fill(x1, y2 - 1, x2, y2, BOX_BORDER);
        g.fill(x1, y1, x1 + 1, y2, BOX_BORDER);
        g.fill(x2 - 1, y1, x2, y2, BOX_BORDER);
    }
}
