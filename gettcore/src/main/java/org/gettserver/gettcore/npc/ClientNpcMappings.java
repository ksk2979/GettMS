package org.gettserver.gettcore.npc;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Client-side copy of the NPC mapping (plain data, safe to reference from common code). */
public final class ClientNpcMappings {
    private ClientNpcMappings() {}

    private static volatile Map<String, String> byUuid = Map.of();
    private static volatile Map<String, String> byName = Map.of();

    public static void set(Map<String, String> uuids, Map<String, String> names) {
        byUuid = Map.copyOf(uuids);
        byName = Map.copyOf(names);
    }

    public static void clear() {
        set(Map.of(), Map.of());
    }

    /** UUID mapping wins over name mapping, same as GettData. */
    public static Optional<String> dialogForNpc(UUID uuid, String name) {
        String u = uuid == null ? null : byUuid.get(uuid.toString());
        if (u != null) return Optional.of(u);
        return Optional.ofNullable(name == null ? null : byName.get(name));
    }
}
