package org.gettserver.gettcore.item;

import java.util.HashMap;
import java.util.Map;

/** 클라이언트가 알고 있는 시전 중인 엔티티 → 시작 시각(클라이언트 게임 틱). */
public final class ClientReturnCasts {
    private static final Map<Integer, Long> CASTS = new HashMap<>();

    private ClientReturnCasts() {}

    public static void set(int entityId, boolean active, long nowTick) {
        if (active) {
            CASTS.putIfAbsent(entityId, nowTick);
        } else {
            CASTS.remove(entityId);
        }
    }

    public static void remove(int entityId) {
        CASTS.remove(entityId);
    }

    public static void clear() {
        CASTS.clear();
    }

    public static Map<Integer, Long> all() {
        return CASTS;
    }
}
