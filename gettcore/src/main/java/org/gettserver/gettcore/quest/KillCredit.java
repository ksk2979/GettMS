package org.gettserver.gettcore.quest;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nullable;

/** 함께 잡기 장부: 몬스터별로 최근 타격한 플레이어를 기록해, 죽을 때 창(WINDOW_MS) 안의 모두에게 킬을 인정한다. */
public final class KillCredit {
    public static final long WINDOW_MS = 30_000;

    private final Map<Integer, Map<UUID, Long>> hits = new HashMap<>();

    public void hit(int victimId, UUID player, long nowMs) {
        hits.computeIfAbsent(victimId, k -> new HashMap<>()).put(player, nowMs);
    }

    /** 죽음: 창 안의 타격자 + killer. 장부에서 victim 제거. */
    public Set<UUID> onDeath(int victimId, @Nullable UUID killer, long nowMs) {
        Set<UUID> out = new HashSet<>();
        Map<UUID, Long> m = hits.remove(victimId);
        if (m != null) {
            m.forEach((u, t) -> {
                if (nowMs - t <= WINDOW_MS) out.add(u);
            });
        }
        if (killer != null) out.add(killer);
        return out;
    }

    public void forget(int victimId) {
        hits.remove(victimId);
    }

    public void prune(long nowMs) {
        hits.values().removeIf(m -> {
            m.values().removeIf(t -> nowMs - t > WINDOW_MS);
            return m.isEmpty();
        });
    }

    public int size() {
        return hits.size();
    }
}
