package org.gettserver.gettcore.quest.client;

/**
 * Height and scroll rules of the quest tracker HUD, in text rows. The main block is pinned on top and never scrolls;
 * the sub-line area below it (after a one-row separator) gets what is left of {@code hud.maxLines}. Pure.
 */
public final class HudScroll {
    public static final int MIN_LINES = 6;
    public static final int MAX_LINES = 30;
    public static final int DEFAULT_LINES = 12;

    private HudScroll() {}

    /** Saved/adjusted box height pushed into {@link #MIN_LINES}..{@link #MAX_LINES}. */
    public static int clampLines(int lines) {
        return Math.max(MIN_LINES, Math.min(lines, MAX_LINES));
    }

    /** Scroll offset (first sub row shown) kept within 0..max(0, subLines - available). */
    public static int clampScroll(int scroll, int subLines, int available) {
        return Math.max(0, Math.min(scroll, Math.max(0, subLines - available)));
    }

    /** Rows left for the sub area: maxLines minus the main block and the separator row (call only when subs show). */
    public static int available(int maxLines, int mainLines) {
        return Math.max(0, maxLines - mainLines - 1);
    }
}
