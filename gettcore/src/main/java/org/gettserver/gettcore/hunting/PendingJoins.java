package org.gettserver.gettcore.hunting;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/**
 * Variants that joined a level before GettData was loaded (spawn chunks load while the server starts). They cannot be
 * checked against their zone yet, so they wait here and are run through the normal join check on the first hunting
 * tick after the data is loaded — otherwise they would never be tracked (zone over-filled, not removed when their
 * zone is deleted). Server thread only.
 */
final class PendingJoins {
    record Pending(UUID id, ResourceKey<Level> dimension, boolean loadedFromDisk) {}

    private final Map<UUID, Pending> pending = new LinkedHashMap<>();

    void add(UUID id, ResourceKey<Level> dimension, boolean loadedFromDisk) {
        pending.putIfAbsent(id, new Pending(id, dimension, loadedFromDisk));
    }

    void remove(UUID id) {
        pending.remove(id);
    }

    int size() {
        return pending.size();
    }

    void clear() {
        pending.clear();
    }

    /** Everything queued, in join order; the queue is empty afterwards. */
    List<Pending> drain() {
        List<Pending> out = new ArrayList<>(pending.values());
        pending.clear();
        return out;
    }
}
