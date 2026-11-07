package org.gettserver.gettcore.quest;

import java.util.Optional;

/** Pure helpers for item/entity objective targets ("namespace:id" or "#namespace:tag"). */
public final class ItemTargets {
    private ItemTargets() {}

    /** A parsed target; {@code id} always carries a namespace ("minecraft:" when omitted). */
    public record Target(boolean tag, String id) {}

    public static Optional<Target> parse(String raw) {
        if (raw == null) return Optional.empty();
        String s = raw.trim();
        boolean tag = s.startsWith("#");
        if (tag) s = s.substring(1);
        if (s.isEmpty() || s.startsWith(":") || s.endsWith(":")) return Optional.empty();
        if (s.indexOf(':') < 0) s = "minecraft:" + s;
        return Optional.of(new Target(tag, s));
    }

    /**
     * How many to take from each slot (in slot order) to remove {@code need} items, given the matching amount in each
     * slot. Takes everything available when there is not enough; the caller checks the sum.
     */
    public static int[] removalPlan(int[] available, int need) {
        int[] take = new int[available.length];
        int left = Math.max(need, 0);
        for (int i = 0; i < available.length && left > 0; i++) {
            int n = Math.min(Math.max(available[i], 0), left);
            take[i] = n;
            left -= n;
        }
        return take;
    }
}
