package org.gettserver.gettcore.enchant;

import org.gettserver.gettcore.client.GuiText;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.StoneCounter;
import org.gettserver.gettcore.reinforce.ClientReinforceConfig;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.neoforged.neoforge.network.PacketDistributor;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.EnchantApplyPayload;

/**
 * Client-only. Tool slot sits on a slowly turning magic circle; a result from the server spins it fast for a second.
 * Only loaded from client paths ({@code GettCoreClient} screen registration and the client-bound result handler).
 */
public class EnchantScreen extends AbstractContainerScreen<EnchantMenu> {
    private static final int PANEL = 0xFFF4E8CC;
    private static final int BORDER = 0xFF4A3B24;
    private static final ResourceLocation CIRCLE = GettCore.id("textures/effect/magic_circle.png");
    private static final int CIRCLE_SIZE = 40;
    private static final int CIRCLE_TEX = 512;
    /** Circle top-left, chosen so its center is the tool slot's center (TOOL_X+8, TOOL_Y+8). */
    private static final int CIRCLE_X = EnchantMenu.TOOL_X + 8 - CIRCLE_SIZE / 2;
    private static final int CIRCLE_Y = EnchantMenu.TOOL_Y + 8 - CIRCLE_SIZE / 2;
    private static final float IDLE_SPEED = 0.5f;
    private static final float EFFECT_SPEED = 12f;
    private static final float IDLE_ALPHA = 0.6f;
    private static final int EFFECT_TICKS = 20;
    private static final int APPLY_COOLDOWN = 20;
    private static final int TEXT = 0xFF4A3B24;
    private static final int SHORT_TEXT = 0xFFB03030;
    /** Width of the left (slot) area; the cost lines are centred in it. */
    private static final int LEFT_W = EnchantMenu.INV_X - 4;
    private static final int TEXT_X = 10;
    private static final int LINE_Y = 52;
    private static final int COST_Y = 80;
    private static final int COST_RIGHT = LEFT_W - 6;

    private Button applyButton;
    /** "레벨만 다시 뽑기". Null while the server has the feature switched off. */
    private Button rerollButton;
    private float angle;
    private float prevAngle;
    private int effectTicks;
    private int cooldown;

    public EnchantScreen(EnchantMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = EnchantMenu.INV_X + 9 * 18 + 8;
        imageHeight = EnchantMenu.INV_Y + 58 + 18 + 6;
        inventoryLabelX = EnchantMenu.INV_X;
        inventoryLabelY = EnchantMenu.INV_Y - 11;
    }

    /** Called by the {@code EnchantResultPayload} handler: plays the effect if this screen is open. */
    public static void onResult(String abilityId, int level) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof EnchantScreen screen) {
            screen.effectTicks = EFFECT_TICKS;
            mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.ENCHANTMENT_TABLE_USE, 1.0f));
        }
    }

    @Override
    protected void init() {
        super.init();
        applyButton = addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.enchant_apply"), b -> {
            PacketDistributor.sendToServer(new EnchantApplyPayload(false));
            cooldown = APPLY_COOLDOWN;
            b.active = false;
        }).bounds(leftPos + 10, topPos + 100, 60, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.enchant_cancel"), b -> onClose())
                .bounds(leftPos + 76, topPos + 100, 60, 18).build());
        rerollButton = null;
        PoolConfig.LevelReroll rule = ClientEnchantConfig.get().pool().levelReroll();
        if (rule.enabled()) {
            rerollButton = addRenderableWidget(Button.builder(
                    Component.translatable("gettcore.gui.enchant_reroll_level"), b -> {
                        PacketDistributor.sendToServer(new EnchantApplyPayload(true));
                        cooldown = APPLY_COOLDOWN;
                        b.active = false;
                    }).bounds(leftPos + 10, topPos + 121, 126, 18)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(Component.translatable(
                            "gettcore.gui.enchant_reroll_level.tip", Gold.format(Math.max(0, rule.goldCost())),
                            Math.max(0, rule.stoneCost()))))
                    .build());
        }
        updateApplyButton();
    }

    private int heldStones() {
        return minecraft == null || minecraft.player == null ? 0
                : StoneCounter.count(StoneCounter.slots(minecraft.player.getInventory()), EnchantMenu::isStone);
    }

    private static long goldCost() {
        return Math.max(0, ClientReinforceConfig.get().enchantGoldCost());
    }

    private void updateApplyButton() {
        applyButton.active = cooldown == 0 && menu.canApply(heldStones(), ClientBalance.get(), goldCost());
        if (rerollButton != null) {
            PoolConfig.LevelReroll rule = ClientEnchantConfig.get().pool().levelReroll();
            boolean leveled = SpecialEnchants.bundleLevel(SpecialEnchants.all(menu.tool())) > 0;
            rerollButton.active = cooldown == 0 && EnchantService.canReroll(rule.enabled(), leveled, heldStones(),
                    Math.max(0, rule.stoneCost()), ClientBalance.get(), Math.max(0, rule.goldCost()));
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (cooldown > 0) cooldown--;
        prevAngle = angle;
        if (effectTicks > 0) {
            angle += EFFECT_SPEED;
            effectTicks--;
        } else {
            angle += IDLE_SPEED;
        }
        if (angle >= 360f) {
            angle -= 360f;
            prevAngle -= 360f;
        }
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
        if (heldStones() > 0 && !menu.tool().isEmpty()) renderToolGlow(g);
        for (Slot slot : menu.slots) {
            int x = leftPos + slot.x;
            int y = topPos + slot.y;
            g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B7A5A);
            g.fill(x, y, x + 16, y + 16, 0xFFC9BA94);
        }
        renderCircle(g, partialTick);
    }

    /** Three translucent white rings around the tool slot (ready to enchant); overlaps brighten toward the slot. */
    private void renderToolGlow(GuiGraphics g) {
        int x = leftPos + EnchantMenu.TOOL_X;
        int y = topPos + EnchantMenu.TOOL_Y;
        for (int r = 7; r >= 3; r -= 2) {
            g.fill(x - r, y - r, x + 16 + r, y + 16 + r, 0x40FFFFFF);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        if (menu.tool().isEmpty()) return;
        int max = LEFT_W - TEXT_X - 4;
        GuiText.big(g, font, Component.translatable("gettcore.screen.enchant.info1"), TEXT_X, LINE_Y, max, TEXT);
        GuiText.big(g, font, Component.translatable("gettcore.screen.enchant.info2"), TEXT_X, LINE_Y + 12, max, TEXT);
        int held = heldStones();
        GuiText.smallRight(g, font, new ItemStack(GettCore.ENCHANT_STONE.get()),
                Component.translatable("gettcore.screen.cost.enchant_stone", 1, held), COST_RIGHT, COST_Y,
                held >= 1 ? TEXT : SHORT_TEXT);
        long gold = goldCost();
        long balance = ClientBalance.get();
        GuiText.smallRight(g, font, ItemStack.EMPTY,
                Component.translatable("gettcore.screen.cost.gold", Gold.format(gold), Gold.format(balance)),
                COST_RIGHT, COST_Y + 9, balance >= gold ? TEXT : SHORT_TEXT);
    }

    private void renderCircle(GuiGraphics g, float partialTick) {
        float a = Mth.lerp(partialTick, prevAngle, angle);
        float alpha = effectTicks > 0 ? 1.0f : IDLE_ALPHA;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(leftPos + CIRCLE_X + CIRCLE_SIZE / 2f, topPos + CIRCLE_Y + CIRCLE_SIZE / 2f, 0);
        pose.mulPose(Axis.ZP.rotationDegrees(a));
        RenderSystem.enableBlend();
        RenderSystem.setShaderColor(1f, 1f, 1f, alpha);
        g.blit(CIRCLE, -CIRCLE_SIZE / 2, -CIRCLE_SIZE / 2, CIRCLE_SIZE, CIRCLE_SIZE,
                0, 0, CIRCLE_TEX, CIRCLE_TEX, CIRCLE_TEX, CIRCLE_TEX);
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableBlend();
        pose.popPose();
    }
}
