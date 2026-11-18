package org.gettserver.gettcore.nick;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * The server nicknames: one per player, unique ignoring case, shown instead of the account name. A nickname is 2 to
 * 12 letters, digits or underscores (any script, so Korean works) with no spaces. Pure: no Minecraft classes.
 */
public final class NicknameBook {
    public static final int MIN = 2;
    public static final int MAX = 12;

    public enum Result { OK, BAD_NAME, TAKEN, SAME }

    private final Map<UUID, String> byId = new HashMap<>();
    private final Map<String, UUID> byKey = new HashMap<>();

    /** Trimmed nickname, or null when it does not fit the rules. */
    public static String clean(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        int n = s.codePointCount(0, s.length());
        if (n < MIN || n > MAX) return null;
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            if (!(Character.isLetterOrDigit(cp) || cp == '_')) return null;
            i += Character.charCount(cp);
        }
        return s;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }

    /**
     * @param reserved lower-case names that belong to someone else for another reason (their account name)
     */
    public Result set(UUID player, String raw, Predicate<String> reserved) {
        String name = clean(raw);
        if (name == null) return Result.BAD_NAME;
        if (name.equals(byId.get(player))) return Result.SAME;
        UUID owner = byKey.get(key(name));
        if (owner != null && !owner.equals(player)) return Result.TAKEN;
        if (reserved != null && reserved.test(key(name))) return Result.TAKEN;
        clear(player);
        byId.put(player, name);
        byKey.put(key(name), player);
        return Result.OK;
    }

    /** True when the player had a nickname. */
    public boolean clear(UUID player) {
        String old = byId.remove(player);
        if (old == null) return false;
        byKey.remove(key(old));
        return true;
    }

    /** The nickname of a player, or null. */
    public String of(UUID player) {
        return byId.get(player);
    }

    /** Who uses this nickname, or null. */
    public UUID owner(String name) {
        return name == null ? null : byKey.get(key(name.trim()));
    }

    public Map<UUID, String> all() {
        return Collections.unmodifiableMap(byId);
    }

    /** Loading: adds a saved nickname unless it is invalid or already used. */
    public void restore(UUID player, String raw) {
        String name = clean(raw);
        if (player == null || name == null || byId.containsKey(player) || byKey.containsKey(key(name))) return;
        byId.put(player, name);
        byKey.put(key(name), player);
    }
}
