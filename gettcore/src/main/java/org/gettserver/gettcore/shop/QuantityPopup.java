package org.gettserver.gettcore.shop;

import java.util.function.IntConsumer;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.economy.Gold;

/** Modal quantity chooser drawn on top of the shop screen. Not a Screen: ShopScreen routes input here. */
public final class QuantityPopup {
    private static final int W = 150;
    private static final int H = 92;

    private final Font font;
    private final Item item;
    private final long unitPrice;
    private final int max;
    private final IntConsumer onConfirm;
    private final Runnable onClose;
    private final EditBox box;
    private final int x;
    private final int y;

    public QuantityPopup(Font font, int screenW, int screenH, Item item, long unitPrice, int max,
                         IntConsumer onConfirm, Runnable onClose) {
        this.font = font;
        this.item = item;
        this.unitPrice = unitPrice;
        this.max = max;
        this.onConfirm = onConfirm;
        this.onClose = onClose;
        this.x = (screenW - W) / 2;
        this.y = (screenH - H) / 2;
        this.box = new EditBox(font, x + 8, y + 28, 60, 16, Component.empty());
        box.setFilter(s -> s.isEmpty() || s.chars().allMatch(Character::isDigit));
        box.setMaxLength(5);
        box.setValue("1");
        box.setFocused(true);
    }

    private int quantity() {
        int v;
        try {
            v = box.getValue().isEmpty() ? 1 : Integer.parseInt(box.getValue());
        } catch (NumberFormatException e) {
            v = max;
        }
        return Math.max(1, Math.min(v, Math.max(1, max)));
    }

    private void set(int v) {
        box.setValue(Integer.toString(Math.max(1, Math.min(v, Math.max(1, max)))));
    }

    private boolean in(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    private void button(GuiGraphics g, int bx, int by, int bw, int bh, Component label, boolean enabled) {
        g.fill(bx, by, bx + bw, by + bh, 0xFF8B6B3A);
        g.fill(bx + 1, by + 1, bx + bw - 1, by + bh - 1, enabled ? 0xFFB98F4E : 0xFF8A8270);
        g.drawCenteredString(font, label, bx + bw / 2, by + (bh - 8) / 2, 0xFFFFFFFF);
    }

    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.pose().pushPose();
        g.pose().translate(0, 0, 400);
        g.fill(x - 1, y - 1, x + W + 1, y + H + 1, 0xFF4A3B24);
        g.fill(x, y, x + W, y + H, 0xFFF4E8CC);
        g.renderItem(new ItemStack(item), x + 8, y + 7);
        g.drawString(font, font.plainSubstrByWidth(item.getDescription().getString(), W - 36), x + 28, y + 11,
                0xFF3B2F1C, false);
        box.render(g, mouseX, mouseY, partialTick);
        button(g, x + 72, y + 28, 16, 16, Component.literal("▲"), true);
        button(g, x + 90, y + 28, 16, 16, Component.literal("▼"), true);
        button(g, x + 110, y + 28, 32, 16, Component.translatable("gettcore.gui.max"), true);
        long total = TradeCalculator.total(unitPrice, quantity());
        g.drawString(font, Component.translatable("gettcore.gui.total", Gold.format(total)), x + 8, y + 52,
                0xFF3B2F1C, false);
        button(g, x + 8, y + 68, 64, 16, Component.translatable("gettcore.gui.confirm"), max > 0);
        button(g, x + 78, y + 68, 64, 16, Component.translatable("gettcore.gui.cancel"), true);
        g.pose().popPose();
    }

    /** Always consumes the click: the popup is modal. */
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return true;
        box.mouseClicked(mx, my, button);
        box.setFocused(true);
        int step = Screen.hasShiftDown() ? 10 : 1;
        if (in(mx, my, x + 72, y + 28, 16, 16)) {
            set(quantity() + step);
        } else if (in(mx, my, x + 90, y + 28, 16, 16)) {
            set(quantity() - step);
        } else if (in(mx, my, x + 110, y + 28, 32, 16)) {
            set(max);
        } else if (in(mx, my, x + 8, y + 68, 64, 16)) {
            confirm();
        } else if (in(mx, my, x + 78, y + 68, 64, 16)) {
            onClose.run();
        }
        return true;
    }

    private void confirm() {
        if (max <= 0) return;
        onConfirm.accept(quantity());
        onClose.run();
    }

    public boolean keyPressed(int key, int scan, int mods) {
        if (key == 256) { // ESC
            onClose.run();
            return true;
        }
        if (key == 257 || key == 335) { // ENTER / KP_ENTER
            confirm();
            return true;
        }
        box.keyPressed(key, scan, mods);
        return true;
    }

    public boolean charTyped(char c, int mods) {
        return box.charTyped(c, mods);
    }
}
