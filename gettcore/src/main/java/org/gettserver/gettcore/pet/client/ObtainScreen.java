package org.gettserver.gettcore.pet.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.client.ClientPetConfig;
import org.gettserver.gettcore.network.ObtainConfirmPayload;
import org.gettserver.gettcore.network.OpenObtainPayload;
import org.gettserver.gettcore.pet.CapturedMobItem;
import org.gettserver.gettcore.pet.PetSpecies;
import org.jetbrains.annotations.Nullable;

/** "펫 획득" screen: the caught mob slowly turning, the obtain chance, and [확정] / [취소]. Client only. */
public class ObtainScreen extends Screen {
    private static final int PANEL_W = 200;
    private static final int PANEL_H = 214;
    private static final int PREVIEW_H = 120;
    private static final int BTN_W = 70;
    private static final int BTN_H = 20;
    private static final int BG = 0xE0101018;
    private static final int BORDER = 0xFF8080A0;
    private static final float DEGREES_PER_TICK = 2.0F;

    private final OpenObtainPayload data;
    private final Component name;
    private final float sizeMultiplier;
    private @Nullable LivingEntity preview;
    private int ticks;

    /** Only called from the client-bound packet handler. */
    public static void open(OpenObtainPayload payload) {
        Minecraft.getInstance().setScreen(new ObtainScreen(payload));
    }

    private ObtainScreen(OpenObtainPayload data) {
        super(Component.translatable("gettcore.gui.obtain.title"));
        this.data = data;
        this.name = CapturedMobItem.displayName(data.key(), data.boss());
        this.sizeMultiplier = data.boss()
                ? ClientPetConfig.species(data.wildEntity()).map(PetSpecies::sizeScale).orElse(1.0).floatValue()
                : 1.0F;
        this.preview = EntityPreview.create(data.wildEntity(), data.key().variant());
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int left() {
        return (width - PANEL_W) / 2;
    }

    private int top() {
        return (height - PANEL_H) / 2;
    }

    @Override
    protected void init() {
        int y = top() + PANEL_H - BTN_H - 8;
        int cx = width / 2;
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.obtain.confirm"), b -> confirm())
                .bounds(cx - BTN_W - 4, y, BTN_W, BTN_H).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.obtain.cancel"), b -> onClose())
                .bounds(cx + 4, y, BTN_W, BTN_H).build());
    }

    private void confirm() {
        PacketDistributor.sendToServer(new ObtainConfirmPayload(
                data.mainHand() ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND));
        onClose();
    }

    @Override
    public void tick() {
        ticks++;
    }

    /** Screen.render draws this, then the buttons: blur first, then the panel, so the buttons stay on top. */
    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        int x1 = left(), y1 = top(), x2 = x1 + PANEL_W, y2 = y1 + PANEL_H;
        g.fill(x1, y1, x2, y2, BG);
        g.fill(x1, y1, x2, y1 + 1, BORDER);
        g.fill(x1, y2 - 1, x2, y2, BORDER);
        g.fill(x1, y1, x1 + 1, y2, BORDER);
        g.fill(x2 - 1, y1, x2, y2, BORDER);

        int cx = width / 2;
        g.drawCenteredString(font, title, cx, y1 + 6, 0xFFFFFF55);
        g.drawCenteredString(font, name, cx, y1 + 18, 0xFFFFFFFF);

        int px1 = x1 + 8, py1 = y1 + 30, px2 = x2 - 8, py2 = py1 + PREVIEW_H;
        float yaw = 180.0F + (ticks + partialTick) * DEGREES_PER_TICK;
        EntityPreview.render(g, px1, py1, px2, py2, previewScale(px2 - px1, py2 - py1), preview, yaw);

        g.drawCenteredString(font, Component.translatable("gettcore.gui.obtain.hint", data.percent()),
                cx, py2 + 8, 0xFFFFFFFF);
    }

    /** Fits a normal mob into about half the box; bosses grow by their size scale, never past the box. */
    private float previewScale(int boxW, int boxH) {
        if (preview == null) return 0;
        float h = Math.max(preview.getBbHeight(), 0.3F);
        float w = Math.max(preview.getBbWidth(), 0.3F);
        float fit = Math.min(boxH / h, boxW / w);
        return Math.min(fit * 0.5F * sizeMultiplier, fit * 0.9F);
    }

    @Override
    public void removed() {
        if (preview != null) {
            preview.discard();
            preview = null;
        }
    }
}
