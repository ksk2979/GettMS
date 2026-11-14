package org.gettserver.gettcore.enchant;

import java.util.ArrayList;
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

/**
 * 특수 능력이 붙은 아이템 툴팁에 효과 설명(과 블록 능력이면 토글 키 안내)을 넣는다. 묶음이면 능력마다 설명을 달고
 * 첫 능력 줄 위에 묶음 이름 줄을 넣는다. 클라이언트 전용.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class AbilityTooltip {
    private AbilityTooltip() {}

    @SubscribeEvent
    public static void onTooltip(ItemTooltipEvent event) {
        if (Minecraft.getInstance().level == null) return;
        List<Rolled> specials = SpecialEnchants.all(event.getItemStack());
        if (specials.isEmpty()) return;

        List<Component> lines = event.getToolTip();
        EnchantConfig config = ClientEnchantConfig.get();
        boolean blockAbility = false;
        for (Rolled special : specials) {
            Ability ability = special.ability();
            int lvl = special.level();
            int found = lineOf(lines, special);
            Object[] args = java.util.Arrays.stream(config.descArgs(ability, lvl))
                    .mapToObj(AbilityTooltip::format).toArray();
            lines.add(found < 0 ? lines.size() : found + 1,
                    Component.translatable("enchantment.gettcore." + ability.id() + ".desc", args)
                            .withStyle(ChatFormatting.GRAY));
            blockAbility |= isBlockAbility(ability);
        }
        // 설명을 다 끼워 넣은 뒤의 위치로 맨 위·맨 아래 능력 줄을 찾는다
        int top = Integer.MAX_VALUE;
        int bottom = -1;
        for (Rolled special : specials) {
            int found = lineOf(lines, special);
            if (found < 0) continue;
            top = Math.min(top, found);
            bottom = Math.max(bottom, found);
        }
        if (blockAbility) {
            // 맨 아래 능력의 설명 줄 다음에 한 번만
            lines.add(bottom < 0 ? lines.size() : Math.min(bottom + 2, lines.size()),
                    Component.translatable("gettcore.tooltip.ability_toggle", AbilityKey.keyName())
                            .withStyle(ChatFormatting.GRAY));
        }
        if (specials.size() < 2 || bottom < 0) return;
        Optional<SpecialEnchants.Tool> tool = SpecialEnchants.toolOf(event.getItemStack());
        if (tool.isEmpty()) return;
        Optional<PoolConfig.Bundle> bundle = SpecialEnchants.bundleOf(specials, config.pool().bundles(tool.get()));
        if (bundle.isEmpty()) return;
        lines.add(top, Component.literal("[").append(BundleNames.display(specials, bundle)).append("]")
                .withStyle(ChatFormatting.GOLD));
    }

    private static int lineOf(List<Component> lines, Rolled special) {
        String name = Component.translatable("enchantment.gettcore." + special.ability().id()).getString();
        String levelText = Component.translatable("enchantment.level." + special.level()).getString();
        return findLine(strings(lines), name, levelText);
    }

    private static List<String> strings(List<Component> lines) {
        List<String> out = new ArrayList<>(lines.size());
        for (Component c : lines) out.add(c.getString());
        return out;
    }

    /**
     * 능력의 인챈트 줄 위치(첫 줄인 아이템 이름은 제외). 줄 전체가 이름이거나 "이름 레벨"일 때만 맞는다:
     * 앞부분만 비교하면 "벌목"이 "벌목 재심기" 줄에, "광맥"이 "광맥 탐지" 줄에 잘못 맞는다. 없으면 -1.
     */
    static int findLine(List<String> lines, String name, String levelText) {
        String withLevel = name + " " + levelText;
        for (int i = 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.equals(name) || line.equals(withLevel)) return i;
        }
        return -1;
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
