package org.gettserver.gettcore.upgrade;

import java.util.Optional;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.client.GuiText;
import org.gettserver.gettcore.economy.ClientBalance;
import org.gettserver.gettcore.economy.Gold;
import org.gettserver.gettcore.economy.StoneCounter;
import org.gettserver.gettcore.network.UpgradeApplyPayload;

/**
 * Client-only. Gear slot top-left; below it two big left-aligned lines (current item, "→ next item") and small
 * right-aligned cost lines (material needed/held with its icon, gold needed/held only when the step costs gold; a
 * short one is red) from {@link ClientUpgradeConfig}. A last tier shows "최대 등급". Same layout as the reinforce
 * screen. Only loaded from {@code GettCoreClient}'s screen registration.
 */
public class UpgradeScreen extends AbstractContainerScreen<UpgradeMenu> {
    private static final int PANEL = 0xFFE4E4E4;
    private static final int BORDER = 0xFF3A3A3A;
    private static final int TEXT = 0xFF303030;
    private static final int MAX_TEXT = 0xFFB07A00;
    private static final int SHORT_TEXT = 0xFFB03030;
    private static final int APPLY_COOLDOWN = 20;
    /** Width of the left (slots) area. */
    private static final int LEFT_W = UpgradeMenu.INV_X - 4;
    private static final int TEXT_X = 10;
    private static final int LINE_Y = 40;
    private static final int COST_Y = 80;
    private static final int COST_RIGHT = LEFT_W - 6;

    private Button applyButton;
    private int cooldown;

    public UpgradeScreen(UpgradeMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        imageWidth = UpgradeMenu.INV_X + 9 * 18 + 8;
        imageHeight = UpgradeMenu.INV_Y + 58 + 18 + 6;
        inventoryLabelX = UpgradeMenu.INV_X;
        inventoryLabelY = UpgradeMenu.INV_Y - 11;
    }

    @Override
    protected void init() {
        super.init();
        applyButton = addRenderableWidget(Button.builder(Component.translatable("gettcore.screen.upgrade.apply"),
                b -> {
                    PacketDistributor.sendToServer(new UpgradeApplyPayload());
                    cooldown = APPLY_COOLDOWN;
                    b.active = false;
                }).bounds(leftPos + 10, topPos + 100, 60, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("gettcore.gui.enchant_cancel"), b -> onClose())
                .bounds(leftPos + 76, topPos + 100, 60, 18).build());
        updateApplyButton();
    }

    private Optional<UpgradeConfig.Step> step() {
        return UpgradeService.nextStep(menu.gear(), ClientUpgradeConfig.get());
    }

    /** 클라이언트 인벤토리의 재료 수. */
    private int held(Optional<UpgradeConfig.Step> step) {
        return minecraft == null || minecraft.player == null ? 0
                : UpgradeService.held(StoneCounter.slots(minecraft.player.getInventory()), step);
    }

    private void updateApplyButton() {
        Optional<UpgradeConfig.Step> step = step();
        applyButton.active = cooldown == 0 && UpgradeRules.canUpgrade(step, held(step), ClientBalance.get());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (cooldown > 0) cooldown--;
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
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        super.renderLabels(g, mouseX, mouseY);
        ItemStack gear = menu.gear();
        if (gear.isEmpty()) return;
        big(g, gear.getItem().getDescription(), LINE_Y, TEXT);
        Optional<UpgradeConfig.Step> found = step();
        Optional<Item> next = found.flatMap(s -> UpgradeService.item(s.to()));
        Optional<Item> material = found.flatMap(s -> UpgradeService.item(s.material()));
        if (found.isEmpty() || next.isEmpty() || material.isEmpty()) {
            big(g, Component.translatable("gettcore.screen.upgrade.max"), LINE_Y + 14, MAX_TEXT);
            return;
        }
        UpgradeConfig.Step step = found.get();
        Component nextName = UpgradeConfig.isBackpackStep(step)
                ? Component.translatable("gettcore.screen.upgrade.backpack_tier",
                        BackpackBridge.tierName(BackpackBridge.tier(gear) + 1))
                : next.get().getDescription();
        big(g, Component.translatable("gettcore.screen.upgrade.next", nextName), LINE_Y + 14, TEXT);
        int held = held(found);
        GuiText.smallRight(g, font, new ItemStack(material.get()),
                Component.translatable("gettcore.screen.cost.upgrade_material", material.get().getDescription(),
                        step.count(), held),
                COST_RIGHT, COST_Y, held >= step.count() ? TEXT : SHORT_TEXT);
        if (step.gold() > 0) {
            long balance = ClientBalance.get();
            GuiText.smallRight(g, font, ItemStack.EMPTY,
                    Component.translatable("gettcore.screen.cost.gold", Gold.format(step.gold()), Gold.format(balance)),
                    COST_RIGHT, COST_Y + 9, balance >= step.gold() ? TEXT : SHORT_TEXT);
        }
    }

    private void big(GuiGraphics g, Component text, int y, int color) {
        GuiText.big(g, font, text, TEXT_X, y, LEFT_W - TEXT_X - 4, color);
    }
}
