package org.gettserver.gettcore.display;

/** Pure rules of the Dream Displays helpers (the mod glue is {@link DisplayService}). */
public final class DisplayRules {
    /** How close to its end a shared (synced, broadcast) video is paused: the check runs twice a second. */
    public static final long END_MARGIN_MS = 700;
    public static final int MAX_URL_LENGTH = 512;

    public static final int ACTION_CLEAR = 0;
    public static final int ACTION_LOOP_TOGGLE = 1;
    public static final int ACTION_PERSONAL = 2;

    private DisplayRules() {}

    /**
     * A shared timeline that is running and whose position (its stored position plus the time since) has come within
     * {@code marginMs} of the end. A paused timeline or one without a known duration never has.
     */
    public static boolean reachedEnd(long positionMs, long serverTimeMs, boolean paused, long durationMs, long nowMs,
                                     long marginMs) {
        if (paused || durationMs <= 0) return false;
        return positionMs + Math.max(0, nowMs - serverTimeMs) >= durationMs - marginMs;
    }

    /** The address a player typed, trimmed; "" when it is blank or too long. */
    public static String cleanUrl(String raw) {
        if (raw == null) return "";
        String url = raw.trim();
        return url.length() > MAX_URL_LENGTH ? "" : url;
    }
}
