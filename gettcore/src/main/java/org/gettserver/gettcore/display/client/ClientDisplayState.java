package org.gettserver.gettcore.display.client;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What this client knows about displays, without touching Dream Displays (so it loads without that mod): which
 * displays repeat their video (from the server) and on which the player watches a personal video. Read from the
 * media threads of Dream Displays, hence the concurrent sets.
 */
public final class ClientDisplayState {
    private static final Set<UUID> LOOPS = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> PERSONAL = ConcurrentHashMap.newKeySet();
    private static volatile Runnable onLoopsChanged = () -> { };

    private ClientDisplayState() {}

    public static void setLoops(Collection<UUID> loops) {
        LOOPS.clear();
        LOOPS.addAll(loops);
        onLoopsChanged.run();
    }

    public static boolean loops(UUID display) {
        return LOOPS.contains(display);
    }

    public static void setPersonal(UUID display, boolean on) {
        if (on) PERSONAL.add(display);
        else PERSONAL.remove(display);
    }

    public static boolean personal(UUID display) {
        return PERSONAL.contains(display);
    }

    /** The open display menu relabels its repeat button. */
    public static void onLoopsChanged(Runnable listener) {
        onLoopsChanged = listener;
    }
}
