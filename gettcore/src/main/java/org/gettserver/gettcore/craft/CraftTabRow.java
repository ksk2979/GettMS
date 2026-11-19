package org.gettserver.gettcore.craft;

/**
 * Which tabs of the crafting screen are on screen. The tabs have different widths (their names), so the row shows as
 * many whole tabs as fit starting at an offset; when not all fit, the screen puts ◁/▷ at the ends and hands the
 * narrower width in between to these methods. Pure.
 */
public final class CraftTabRow {
    private CraftTabRow() {}

    /** Whether the tabs, {@code gap} apart, are wider than {@code avail}. */
    public static boolean overflows(int[] widths, int avail, int gap) {
        int sum = 0;
        for (int i = 0; i < widths.length; i++) sum += widths[i] + (i > 0 ? gap : 0);
        return sum > avail;
    }

    /** Tabs shown starting at {@code offset}: as many whole ones as fit, and at least one. */
    public static int visible(int[] widths, int offset, int avail, int gap) {
        int used = 0;
        int n = 0;
        for (int i = Math.max(0, offset); i < widths.length; i++) {
            int next = used + widths[i] + (n > 0 ? gap : 0);
            if (n > 0 && next > avail) break;
            used = next;
            n++;
        }
        return n;
    }

    /** The largest useful offset: the first one from which every remaining tab fits. */
    public static int maxOffset(int[] widths, int avail, int gap) {
        for (int offset = 0; offset < widths.length; offset++) {
            if (offset + visible(widths, offset, avail, gap) >= widths.length) return offset;
        }
        return 0;
    }

    public static int clampOffset(int offset, int[] widths, int avail, int gap) {
        return Math.max(0, Math.min(offset, maxOffset(widths, avail, gap)));
    }

    /** The offset moved only as far as it takes to show tab {@code selected}. */
    public static int ensureVisible(int offset, int selected, int[] widths, int avail, int gap) {
        offset = clampOffset(offset, widths, avail, gap);
        if (selected < 0 || selected >= widths.length) return offset;
        if (selected < offset) return selected;
        while (selected >= offset + visible(widths, offset, avail, gap)) offset++;
        return offset;
    }
}
