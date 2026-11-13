package org.gettserver.gettcore.detector;

import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.item.ItemStack;

/** 탐지기 툴팁 이미지: 대상 칸 하나(비어 있으면 빈 칸). */
public record DetectorTooltip(ItemStack target) implements TooltipComponent {}
