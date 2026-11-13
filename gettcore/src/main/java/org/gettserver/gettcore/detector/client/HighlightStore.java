package org.gettserver.gettcore.detector.client;

import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.function.LongPredicate;

/** 순수 저장소: 묶음 key → (좌표 집합, 만료 게임 시각). 규칙은 {@code DetectorHighlightPayload} 주석과 같다. */
public final class HighlightStore {
    private record Bundle(LongSet positions, long expiresAt) {}

    private final Map<String, Bundle> bundles = new HashMap<>();

    public void apply(String key, long[] positions, int ticks, long now) {
        if (ticks <= 0 && key.isEmpty()) {
            bundles.clear();
        } else if (ticks <= 0 || positions.length == 0) {
            bundles.remove(key);
        } else {
            LongSet set = new LongOpenHashSet(positions.length);
            for (long p : positions) set.add(p);
            bundles.put(key, new Bundle(set, now + ticks));
        }
    }

    /** 만료된 묶음을 지운다. 바뀌었으면 true. */
    public boolean expire(long now) {
        return bundles.values().removeIf(b -> now >= b.expiresAt);
    }

    /** 조건에 맞는 좌표를 지우고, 비어 버린 묶음도 지운다. 바뀌었으면 true. */
    public boolean remove(LongPredicate gone) {
        boolean changed = false;
        for (Iterator<Bundle> it = bundles.values().iterator(); it.hasNext(); ) {
            LongSet set = it.next().positions;
            if (set.removeIf(gone::test)) changed = true;
            if (set.isEmpty()) it.remove();
        }
        return changed;
    }

    public LongSet union() {
        LongSet all = new LongOpenHashSet();
        for (Bundle b : bundles.values()) all.addAll(b.positions);
        return all;
    }

    public void clear() {
        bundles.clear();
    }

    public boolean isEmpty() {
        return bundles.isEmpty();
    }
}
