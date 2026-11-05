package org.gettserver.gettcore.shop;

import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Client helper: counts sellable items in the inventory. Task 9 replaces this with TradeCalculator. */
public final class InventoryMatch {
    private InventoryMatch() {}

    public static boolean matches(ItemStack stack, Item item) {
        return !stack.isEmpty() && stack.getItem() == item && stack.getComponentsPatch().isEmpty();
    }

    public static int count(Inventory inv, Item item) {
        int total = 0;
        for (ItemStack s : inv.items) {
            if (matches(s, item)) total += s.getCount();
        }
        return total;
    }
}
