package org.gettserver.gettcore.craft;

import java.util.Arrays;
import java.util.Comparator;

/**
 * Which inventory stacks pay for a recipe. Pure: the caller says how many items each stack holds and which stacks
 * each ingredient accepts.
 *
 * <p>Two ingredients can accept the same stack (a plain "musket" and a "musket +5"), so the amounts are handed out
 * one ingredient at a time, the pickiest first (fewest accepting stacks), and never twice from the same items. Within
 * an ingredient the stacks are used in the order given, so the caller lists the ones it would rather spend first
 * (lowest reinforce level).
 */
public final class CraftRules {
    private CraftRules() {}

    /**
     * @param counts  items in each stack
     * @param accepts {@code accepts[ingredient][stack]}
     * @param need    items each ingredient needs
     * @return {@code take[ingredient][stack]}, or null when the stacks cannot pay for everything
     */
    public static int[][] allocate(int[] counts, boolean[][] accepts, int[] need) {
        int[] left = counts.clone();
        int[][] take = new int[need.length][counts.length];
        Integer[] order = new Integer[need.length];
        for (int i = 0; i < order.length; i++) order[i] = i;
        Arrays.sort(order, Comparator.comparingInt((Integer i) -> accepting(accepts[i])).thenComparingInt(i -> i));
        for (int ing : order) {
            int want = need[ing];
            for (int s = 0; s < left.length && want > 0; s++) {
                if (!accepts[ing][s] || left[s] <= 0) continue;
                int n = Math.min(want, left[s]);
                take[ing][s] = n;
                left[s] -= n;
                want -= n;
            }
            if (want > 0) return null;
        }
        return take;
    }

    /** Items an ingredient could use if it were alone (what the screen shows as "have"). */
    public static int available(int[] counts, boolean[] accepts) {
        long sum = 0;
        for (int s = 0; s < counts.length; s++) if (accepts[s]) sum += counts[s];
        return (int) Math.min(Integer.MAX_VALUE, sum);
    }

    private static int accepting(boolean[] row) {
        int n = 0;
        for (boolean b : row) if (b) n++;
        return n;
    }
}
