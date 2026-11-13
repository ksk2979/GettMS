package org.gettserver.gettcore.detector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 플레이어·key 별로 "다시 쓸 수 있는 게임 시각"을 기억한다. 시각은 호출자가 넘기므로 순수하다. */
public final class DetectorCooldowns {
    private final Map<UUID, Map<String, Long>> readyAt = new HashMap<>();

    public long remainingTicks(UUID player, String key, long now) {
        Map<String, Long> keys = readyAt.get(player);
        if (keys == null) return 0;
        Long ready = keys.get(key);
        return ready == null ? 0 : Math.max(0, ready - now);
    }

    public void start(UUID player, String key, long now, long cooldownTicks) {
        if (cooldownTicks <= 0) {
            Map<String, Long> keys = readyAt.get(player);
            if (keys != null) keys.remove(key);
            return;
        }
        readyAt.computeIfAbsent(player, p -> new HashMap<>()).put(key, now + cooldownTicks);
    }

    /** 쓰지 않는다(쿨타임은 재접속해도 유지). */
    public void forget(UUID player) {
        readyAt.remove(player);
    }

    public void clear() {
        readyAt.clear();
    }
}
