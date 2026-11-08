package org.gettserver.gettcore.reinforce;

import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.network.ReinforceApplyPayload;

/**
 * Client-only. Gear slot + stone slot, three centred info lines (level, effect at the next level, odds/cost) from
 * {@link ClientReinforceConfig}; a result from the server flashes the gear slot for one second (green = success,
 * red = fail). Only loaded from client paths ({@code GettCoreClient} screen registration and the client-bound
 * result handler). Gun classification uses {@link ReinforceTargets#ntglWeapon}, which stays false until the NTGL
 * bridge fills it.
 */
public class ReinforceScreen extends AbstractContainerScreen<ReinforceMenu> {
    private static final int PANEL = 0xFFE4E4E4;
    private static final int BORDER = 0xFF3A3A3A;
    private static final int TEXT = 0xFF303030;
    private static final int MAX_TEXT = 0xFFB07A00;
    private static final int FLASH_TICKS = 20;
    private static final int APPLY_COOLDOWN = 20;
    /** Width of the left (slots) area; the info lines are centred in it. */
    private static final int LEFT_W = ReinforceMenu.INV_X - 4;
    private static final int LINE_Y = 64;

    private Button applyButton;
    private int flashTicks;
    private boolean flashSuccess;
    private int cooldown;

    public ReinforceScreen(ReinforceMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = ReinforceMenu.INV_X + 9 * 18 + 8;
        imageHeight = ReinforceMenu.INV_Y + 58 + 18 + 6;
        inventoryLabelX = ReinforceMenu.INV_X;
        inventoryLabelY = ReinforceMenu.INV_Y - 11;
    }

    /** Called by the {@code ReinforceResultPayload} handler: flashes the gear slot if this screen is open. */
    public static void onResult(boolean success, int level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ReinforceScreen screen) {
            screen.flashTicks = FLASH_TICKS;
            screen.flashSuccess = success;
        }
    }

    @Override
    protected void init() {
        super.init();
        applyButton = addRenderableWidget(Button.builder(Component.translatable("gettcore.screen.reinforce.apply"),
                b -> {
                    PacketDistributor.sendToServer(new ReinforceApplyPayload());
                    cooldown = APPLY_COOLDOWN;
                    b.active = false;
                }).bounds(leftPos + 10, topPos + 100, 60, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.enchant_cancel"), b -> onClose())
                .bounds(leftPos + 76, topPos + 100, 60, 18).build());
        updateApplyButton();
    }

    private void updateApplyButton() {
        applyButton.active = cooldown == 0 && menu.canApply(ClientReinforceConfig.get());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (cooldown > 0) cooldown--;
        if (flashTicks > 0) flashTicks--;
        updateApplyButton();
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        renderTooltip(g, mouseX, mouseY);
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        g.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, BORDER);
        g.fill(leftPos + 1, topPos + 1, leftPos + imageWidth - 1, topPos + imageHeight - 1, PANEL);
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF7A7A7A);
            g.fill(x, y, x + 16, y + 16, 0xFFB8B8B8);
        }
        if (flashTicks > 0) renderFlash(g, partialTick);
    }

    /** Fading square around the gear slot. */
    private void renderFlash(GuiGraphics g, float partialTick) {
        float t = Math.max(0f, (flashTicks - partialTick) / FLASH_TICKS);
        int alpha = (int) (t * 0xC0) & 0xFF;
        int rgb = flashSuccess ? 0x60FF60 : 0xFF4040;
        int x = leftPos + ReinforceMenu.GEAR_X;
        int y = topPos + ReinforceMenu.GEAR_Y;
        g.fill(x - 6, y - 6, x + 22, y + 22, (alpha << 24) | rgb);
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        ItemStack gear = menu.gear();
        Optional<ReinforceRules.Target> target = ReinforceTargets.target(gear);
        if (target.isEmpty()) return;
        ReinforceConfig config = ClientReinforceConfig.get();
        int level = ReinforceRules.level(gear);
        if (!ReinforceRules.canReinforce(level, config)) {
            centred(g, Component.translatable("gettcore.screen.reinforce.max"), LINE_Y, MAX_TEXT);
            return;
        }
        int next = level + 1;
        centred(g, Component.translatable("gettcore.screen.reinforce.level", level, next), LINE_Y, TEXT);
        centred(g, effectLine(target.get(), next, config), LINE_Y + 11, TEXT);
        centred(g, Component.translatable("gettcore.screen.reinforce.odds",
                fmt(ReinforceRules.successPercent(level, config)), ReinforceRules.stoneCost(level, config)),
                LINE_Y + 22, TEXT);
    }

    /** Effect of the item at level {@code next} (total, not the increment). */
    private static Component effectLine(ReinforceRules.Target target, int next, ReinforceConfig config) {
        return switch (target) {
            case GUN -> Component.translatable("gettcore.screen.reinforce.effect.gun",
                    fmt(next * config.gunDamagePercentPerLevel()));
            case MELEE -> Component.translatable("gettcore.screen.reinforce.effect.melee",
                    fmt(next * config.meleeDamagePercentPerLevel()));
            case SHIELD -> Component.translatable("gettcore.screen.reinforce.effect.shield",
                    fmt(next * config.shieldSpeedPercentPerLevel()));
            case ARMOR -> Component.translatable("gettcore.screen.reinforce.effect.armor",
                    fmt(ReinforceRules.armorHealth(next, config)));
        };
    }

    private void centred(GuiGraphics g, Component text, int y, int color) {
        g.drawString(font, text, (LEFT_W - font.width(text)) / 2, y, color, false);
    }

    /** 10.0 → "10", 1.5 → "1.5", 2.25 → "2.25". */
    static String fmt(double v) {
        return ReinforceEffects.fmt(v);
    }
}
