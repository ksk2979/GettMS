package org.gettserver.gettcore.economy;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class RateLimiter {
    private final long windowMillis;
    private final Map<UUID, Long> last = new HashMap<>();

    public RateLimiter(long windowMillis) {
        this.windowMillis = windowMillis;
    }

    public synchronized boolean tryAcquire(UUID id, long nowMillis) {
        Long prev = last.get(id);
        if (prev != null && nowMillis - prev < windowMillis) return false;
        last.put(id, nowMillis);
        return true;
    }
}
