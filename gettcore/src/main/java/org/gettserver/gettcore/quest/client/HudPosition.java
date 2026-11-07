package org.gettserver.gettcore.quest.client;

import java.util.Locale;
import java.util.Properties;

/**
 * Where the quest tracker HUD sits: the nearest screen corner plus the distance from that corner (GUI pixels, never
 * negative). Stored per corner so a window resize keeps the box near the same edge. Pure; no Minecraft classes.
 */
public record HudPosition(Anchor anchor, int x, int y) {
    public enum Anchor { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public static final String KEY_ANCHOR = "hud.anchor";
    public static final String KEY_X = "hud.x";
    public static final String KEY_Y = "hud.y";
    /** The original placement: top right, 4px from both edges. */
    public static final HudPosition DEFAULT = new HudPosition(Anchor.TOP_RIGHT, 4, 4);

    public HudPosition {
        if (anchor == null) anchor = Anchor.TOP_RIGHT;
        x = Math.max(0, x);
        y = Math.max(0, y);
    }

    public boolean isDefault() {
        return equals(DEFAULT);
    }

    private boolean right() {
        return anchor == Anchor.TOP_RIGHT || anchor == Anchor.BOTTOM_RIGHT;
    }

    private boolean bottom() {
        return anchor == Anchor.BOTTOM_LEFT || anchor == Anchor.BOTTOM_RIGHT;
    }

    /** Absolute left edge for a box of width {@code boxW}, pushed inside the screen. */
    public int left(int screenW, int boxW) {
        return clamp(right() ? screenW - boxW - x : x, screenW - boxW);
    }

    /** Absolute top edge for a box of height {@code boxH}, pushed inside the screen. */
    public int top(int screenH, int boxH) {
        return clamp(bottom() ? screenH - boxH - y : y, screenH - boxH);
    }

    /** Position for a box drawn at (left, top): nearest corner by the box centre, distances from that corner. */
    public static HudPosition fromAbsolute(int left, int top, int boxW, int boxH, int screenW, int screenH) {
        left = clamp(left, screenW - boxW);
        top = clamp(top, screenH - boxH);
        boolean right = left + boxW / 2.0 > screenW / 2.0;
        boolean bottom = top + boxH / 2.0 > screenH / 2.0;
        Anchor a = bottom ? (right ? Anchor.BOTTOM_RIGHT : Anchor.BOTTOM_LEFT)
                : (right ? Anchor.TOP_RIGHT : Anchor.TOP_LEFT);
        int dx = right ? screenW - boxW - left : left;
        int dy = bottom ? screenH - boxH - top : top;
        return new HudPosition(a, dx, dy);
    }

    /** 0 .. max; when the box is larger than the screen it sticks to the top/left edge. */
    private static int clamp(int v, int max) {
        return Math.max(0, Math.min(v, max));
    }

    public void write(Properties props) {
        props.setProperty(KEY_ANCHOR, anchor.name().toLowerCase(Locale.ROOT));
        props.setProperty(KEY_X, Integer.toString(x));
        props.setProperty(KEY_Y, Integer.toString(y));
    }

    /** Reads the position; a missing or broken value falls back to {@link #DEFAULT} as a whole. */
    public static HudPosition read(Properties props) {
        String a = props.getProperty(KEY_ANCHOR);
        String sx = props.getProperty(KEY_X);
        String sy = props.getProperty(KEY_Y);
        if (a == null || sx == null || sy == null) return DEFAULT;
        try {
            Anchor anchor = Anchor.valueOf(a.trim().toUpperCase(Locale.ROOT));
            int x = Integer.parseInt(sx.trim());
            int y = Integer.parseInt(sy.trim());
            if (x < 0 || y < 0) return DEFAULT;
            return new HudPosition(anchor, x, y);
        } catch (IllegalArgumentException e) {
            return DEFAULT;
        }
    }
}
