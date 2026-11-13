package org.gettserver.gettcore.detector.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.tooltip.ClientTooltipComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.gettserver.gettcore.detector.DetectorTooltip;

/** 18x18 칸 테두리(바닐라 번들 칸 스프라이트) + 대상 아이템 아이콘. */
public final class ClientDetectorTooltip implements ClientTooltipComponent {
    private static final ResourceLocation SLOT = ResourceLocation.withDefaultNamespace("container/bundle/slot");
    private static final int SIZE = 18;

    private final ItemStack target;

    public ClientDetectorTooltip(DetectorTooltip tooltip) {
        this.target = tooltip.target();
    }

    @Override
    public int getHeight() {
        return SIZE + 4;
    }

    @Override
    public int getWidth(Font font) {
        return SIZE;
    }

    @Override
    public void renderImage(Font font, int x, int y, GuiGraphics graphics) {
        graphics.blitSprite(SLOT, x, y + 2, SIZE, SIZE);
        if (!target.isEmpty()) {
            graphics.renderItem(target, x + 1, y + 3, 0);
            graphics.renderItemDecorations(font, target, x + 1, y + 3);
        }
    }
}
