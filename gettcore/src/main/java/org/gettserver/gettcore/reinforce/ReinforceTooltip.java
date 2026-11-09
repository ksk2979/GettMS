package org.gettserver.gettcore.reinforce;

import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.reinforce.ReinforceRules.Target;

/**
 * 강화된 장비 툴팁: 첫 줄(이름)을 금색 "+N 이름" 으로 바꾸고 그 아래 현재 단계 효과 한 줄. 수치는 동기화된
 * {@link ClientReinforceConfig}. 클라이언트 전용(핫바 위 이름은 건드리지 않는다).
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class ReinforceTooltip {
    private ReinforceTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        int level = ReinforceRules.level(stack);
        if (level <= 0) return;
        List<Component> lines = event.getToolTip();
        if (lines.isEmpty()) return;
        lines.set(0, Component.literal("+" + level + " ").withStyle(ChatFormatting.GOLD)
                .append(lines.get(0).copy().withStyle(ChatFormatting.GOLD)));
        Optional<Target> target = ReinforceTargets.target(stack);
        if (target.isEmpty()) return;
        ReinforceConfig cfg = ClientReinforceConfig.get();
        lines.add(1, Component.translatable(ReinforceEffects.tooltipKey(target.get()), level,
                ReinforceEffects.fmt(ReinforceEffects.tooltipValue(target.get(), level, cfg)),
                ReinforceEffects.fmt(ReinforceRules.armorDefense(level, cfg)))
                .withStyle(ChatFormatting.GRAY));
        if (target.get() == Target.SHIELD)
            lines.add(2, Component.translatable(ReinforceEffects.SHIELD_NOTE_KEY).withStyle(ChatFormatting.GRAY));
    }
}
