package org.gettserver.gettcore.economy;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * 재료(강화석·마법부여석)를 칸에 넣지 않고 플레이어 인벤토리(주 인벤토리 + 핫바 + 왼손)에서 세고 뺀다. 빼기는 전부 아니면
 * 전무: 모자라면 아무것도 건드리지 않는다.
 */
public final class StoneCounter {
    private StoneCounter() {}

    /** 세고 뺄 칸들(원본 스택 참조): 주 인벤토리·핫바 36칸 + 왼손. */
    public static List<ItemStack> slots(Inventory inv) {
        List<ItemStack> out = new ArrayList<>(inv.items.size() + inv.offhand.size());
        out.addAll(inv.items);
        out.addAll(inv.offhand);
        return out;
    }

    public static int count(List<ItemStack> stacks, Predicate<ItemStack> match) {
        return total(counts(stacks, match));
    }

    /** {@code n} 개를 앞 칸부터 뺀다. 모자라면 아무것도 빼지 않고 false. */
    public static boolean consume(List<ItemStack> stacks, Predicate<ItemStack> match, int n) {
        int[] plan = takePlan(counts(stacks, match), n);
        if (plan == null) return false;
        for (int i = 0; i < plan.length; i++) {
            if (plan[i] > 0) stacks.get(i).shrink(plan[i]);
        }
        return true;
    }

    private static int[] counts(List<ItemStack> stacks, Predicate<ItemStack> match) {
        int[] c = new int[stacks.size()];
        for (int i = 0; i < c.length; i++) {
            ItemStack s = stacks.get(i);
            c[i] = !s.isEmpty() && match.test(s) ? s.getCount() : 0;
        }
        return c;
    }

    static int total(int[] counts) {
        int sum = 0;
        for (int c : counts) sum += c;
        return sum;
    }

    /** 칸별로 뺄 개수(앞 칸부터), 합이 {@code n} 미만이면 null. */
    static int[] takePlan(int[] counts, int n) {
        if (total(counts) < n) return null;
        int[] plan = new int[counts.length];
        int left = Math.max(0, n);
        for (int i = 0; i < counts.length && left > 0; i++) {
            plan[i] = Math.min(counts[i], left);
            left -= plan[i];
        }
        return plan;
    }
}
