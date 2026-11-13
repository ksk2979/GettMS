package org.gettserver.gettcore.enchant;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Rolled;

/** 특수 능력이 붙은 아이템 툴팁에 효과 설명(과 블록 능력이면 토글 키 안내)을 넣는다. 클라이언트 전용. */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class AbilityTooltip {
    private AbilityTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        var level = Minecraft.getInstance().level;
        if (level == null) return;
        Optional<Rolled> special = SpecialEnchants.current(event.getItemStack(), level.registryAccess());
        if (special.isEmpty()) return;
        Ability ability = special.get().ability();

        List<Component> lines = event.getToolTip();
        String name = Component.translatable("enchantment.gettcore." + ability.id()).getString();
        int at = lines.size();
        for (int i = 1; i < lines.size(); i++) {
            if (lines.get(i).getString().startsWith(name)) {
                at = i + 1;
                break;
            }
        }
        EnchantConfig config = ClientEnchantConfig.get();
        int lvl = special.get().level();
        Object[] args = java.util.Arrays.stream(config.descArgs(ability, lvl))
                .mapToObj(AbilityTooltip::format).toArray();
        lines.add(at++, Component.translatable("enchantment.gettcore." + ability.id() + ".desc", args)
                .withStyle(ChatFormatting.GRAY));
        if (isBlockAbility(ability)) {
            lines.add(at, Component.translatable("gettcore.tooltip.ability_toggle", AbilityKey.keyName())
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static boolean isBlockAbility(Ability a) {
        return a == Ability.HARVEST || a == Ability.VEIN || a == Ability.AREA || a == Ability.TIMBER
                || a == Ability.PROSPECT;
    }

    private static String format(double v) {
        if (v == Math.rint(v) && !Double.isInfinite(v)) return Long.toString((long) v);
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
