package org.gettserver.gettcore.enchant;

import java.util.List;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.enchantment.ItemEnchantments;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;

/**
 * 특수 능력은 책으로 존재하지 않는다(모루에서도 붙지 않는다, {@link AnvilRules}). 바닐라는 등록된 모든 인챈트의 책을
 * 크리에이티브 탭에 넣으므로 거기서 뺀다. 전리품·인챈트 테이블·주민 거래는 바닐라 태그에 넣지 않아 원래 나오지 않는다.
 */
public final class SpecialBookFilter {
    private SpecialBookFilter() {}

    public static void onBuildTabs(BuildCreativeModeTabContentsEvent event) {
        for (ItemStack stack : List.copyOf(event.getParentEntries())) {
            if (isSpecialBook(stack)) event.remove(stack, CreativeModeTab.TabVisibility.PARENT_TAB_ONLY);
        }
        for (ItemStack stack : List.copyOf(event.getSearchEntries())) {
            if (isSpecialBook(stack)) event.remove(stack, CreativeModeTab.TabVisibility.SEARCH_TAB_ONLY);
        }
    }

    /** 태그가 아직 묶이지 않았을 수 있어 키로 판단한다. */
    static boolean isSpecialBook(ItemStack stack) {
        if (!stack.is(Items.ENCHANTED_BOOK)) return false;
        ItemEnchantments stored = stack.getOrDefault(DataComponents.STORED_ENCHANTMENTS, ItemEnchantments.EMPTY);
        return stored.keySet().stream().anyMatch(h -> h.unwrapKey().flatMap(Ability::byKey).isPresent());
    }
}
