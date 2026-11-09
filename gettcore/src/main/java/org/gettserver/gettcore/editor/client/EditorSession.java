package org.gettserver.gettcore.editor.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Whether an editor page is still open. Each page (browser tab) sends a heartbeat every ~15 s with its dirty state and
 * a close beacon when it goes away; the session ends
 * <ul>
 * <li>{@link #CLOSE_GRACE} after the last open page closed (the grace lets a reload's new page take over),
 * <li>or when no page sent a heartbeat for {@link #HEARTBEAT_TIMEOUT} (browser killed, beacon lost) — or
 * {@link #HIDDEN_TIMEOUT} if the page said it is in a background tab, where browsers slow timers to ~1/min.
 * </ul>
 * {@link End#unsaved()} = the last page that went away still had unsaved changes. Before the first heartbeat (an
 * older page) nothing is decided here. Pure and thread-safe; times are {@link System#nanoTime()} values.
 */
public final class EditorSession {
    public static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(60);
    public static final Duration HIDDEN_TIMEOUT = Duration.ofMinutes(30);
    public static final Duration CLOSE_GRACE = Duration.ofSeconds(5);
    static final int MAX_PAGES = 32;
    private static final Pattern PAGE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    public record Report(String page, boolean dirty, boolean hidden) {}

    public record End(boolean unsaved, String reason) {}

    private record Page(long last, boolean dirty, boolean hidden) {}

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private boolean seen;
    private long lastGone = Long.MIN_VALUE;
    private boolean lastGoneDirty;
    private String lastGoneReason = "";

    /** A heartbeat body: {@code {"page": id, "dirty": bool, "hidden": bool}}; empty when malformed. */
    public static Optional<Report> parse(String body) {
        try {
            JsonElement e = JsonParser.parseString(body);
            if (!e.isJsonObject()) return Optional.empty();
            JsonObject o = e.getAsJsonObject();
            JsonElement p = o.get("page");
            if (p == null || !p.isJsonPrimitive() || !PAGE_ID.matcher(p.getAsString()).matches()) return Optional.empty();
            return Optional.of(new Report(p.getAsString(), bool(o, "dirty"), bool(o, "hidden")));
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    private static boolean bool(JsonObject o, String k) {
        JsonElement v = o.get(k);
        return v != null && v.isJsonPrimitive() && v.getAsJsonPrimitive().isBoolean() && v.getAsBoolean();
    }

    public synchronized boolean seen() {
        return seen;
    }

    public synchronized void heartbeat(String page, boolean dirty, boolean hidden, long now) {
        seen = true;
        if (!pages.containsKey(page) && pages.size() >= MAX_PAGES) pages.remove(pages.keySet().iterator().next());
        pages.put(page, new Page(now, dirty, hidden));
    }

    public synchronized void close(String page, boolean dirty, long now) {
        seen = true;
        pages.remove(page);
        gone(dirty, "page closed", now);
    }

    private void gone(boolean dirty, String reason, long at) {
        lastGone = at;
        lastGoneDirty = dirty;
        lastGoneReason = reason;
    }

    /** Drops pages that stopped sending heartbeats; returns the end of the session once no page is left. */
    public synchronized Optional<End> check(long now) {
        if (!seen) return Optional.empty();
        pages.entrySet().removeIf(en -> {
            Page p = en.getValue();
            long limit = (p.hidden() ? HIDDEN_TIMEOUT : HEARTBEAT_TIMEOUT).toNanos();
            if (now - p.last() < limit) return false;
            gone(p.dirty(), "no heartbeat", now);
            return true;
        });
        if (!pages.isEmpty()) return Optional.empty();
        if (lastGone == Long.MIN_VALUE) return Optional.empty();
        if (lastGoneReason.equals("page closed") && now - lastGone < CLOSE_GRACE.toNanos()) return Optional.empty();
        return Optional.of(new End(lastGoneDirty, lastGoneReason));
    }
}
