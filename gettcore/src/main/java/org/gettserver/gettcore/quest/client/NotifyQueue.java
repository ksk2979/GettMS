package org.gettserver.gettcore.quest.client;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * FIFO that releases at most one item per {@code spacing} ticks (the first one at once). Items over {@code capacity}
 * are dropped, oldest kept. Pure; driven by a tick counter.
 */
public final class NotifyQueue<T> {
    private final int spacing;
    private final int capacity;
    private final Deque<T> items = new ArrayDeque<>();
    private long lastShown;
    private boolean shownAny;

    public NotifyQueue(int spacing, int capacity) {
        this.spacing = spacing;
        this.capacity = capacity;
    }

    public void offer(T item) {
        if (items.size() < capacity) items.addLast(item);
    }

    /** The next item if one is waiting and {@code spacing} ticks passed since the last one, else null. */
    public T poll(long now) {
        if (items.isEmpty()) return null;
        if (shownAny && now - lastShown < spacing) return null;
        shownAny = true;
        lastShown = now;
        return items.pollFirst();
    }

    public int size() {
        return items.size();
    }

    public void clear() {
        items.clear();
        shownAny = false;
    }
}
