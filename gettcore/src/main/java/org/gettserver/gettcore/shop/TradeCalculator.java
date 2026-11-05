package org.gettserver.gettcore.shop;

import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/** Pure trade math, shared by the server (authoritative) and the client (previews). */
public final class TradeCalculator {
    private TradeCalculator() {}

    public static boolean matches(ItemStack stack, ResourceLocation itemId) {
        return !stack.isEmpty()
                && BuiltInRegistries.ITEM.getKey(stack.getItem()).equals(itemId)
                && stack.getComponentsPatch().isEmpty();
    }

    public static int countSellable(List<ItemStack> inv, ResourceLocation itemId) {
        long total = 0;
        for (ItemStack s : inv) {
            if (matches(s, itemId)) total += s.getCount();
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    public static int capacityFor(List<ItemStack> inv, Item item, int maxStack) {
        long total = 0;
        for (ItemStack s : inv) {
            if (s.isEmpty()) {
                total += maxStack;
            } else if (s.getItem() == item && s.getComponentsPatch().isEmpty()) {
                total += Math.max(0, maxStack - s.getCount());
            }
        }
        return (int) Math.min(total, Integer.MAX_VALUE);
    }

    public static int maxBuyable(long balance, long unitPrice, int capacity, int requested) {
        if (unitPrice <= 0 || balance <= 0 || capacity <= 0 || requested <= 0) return 0;
        long affordable = balance / unitPrice;
        return (int) Math.min(Math.min((long) requested, (long) capacity), affordable);
    }

    public static long total(long unitPrice, int count) {
        try {
            return Math.multiplyExact(unitPrice, (long) count);
        } catch (ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    /** Places up to n items into the list (partial stacks first, then empty slots); returns how many were placed. */
    public static int distribute(List<ItemStack> inv, Item item, int maxStack, int n) {
        int left = n;
        for (int i = 0; i < inv.size() && left > 0; i++) {
            ItemStack s = inv.get(i);
            if (!s.isEmpty() && s.getItem() == item && s.getComponentsPatch().isEmpty() && s.getCount() < maxStack) {
                int add = Math.min(left, maxStack - s.getCount());
                s.grow(add);
                left -= add;
            }
        }
        for (int i = 0; i < inv.size() && left > 0; i++) {
            if (inv.get(i).isEmpty()) {
                int add = Math.min(left, maxStack);
                inv.set(i, new ItemStack(item, add));
                left -= add;
            }
        }
        return n - left;
    }
}
