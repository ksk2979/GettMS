package org.gettserver.gettcore.economy;

import com.mojang.authlib.GameProfile;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

public final class OfflinePlayers {
    private OfflinePlayers() {}

    public static Optional<GameProfile> resolve(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) return Optional.of(online.getGameProfile());
        return server.getProfileCache() == null ? Optional.empty() : server.getProfileCache().get(name);
    }

    public static String name(MinecraftServer server, UUID id) {
        ServerPlayer online = server.getPlayerList().getPlayer(id);
        if (online != null) return online.getGameProfile().getName();
        if (server.getProfileCache() != null) {
            Optional<GameProfile> p = server.getProfileCache().get(id);
            if (p.isPresent()) return p.get().getName();
        }
        return id.toString();
    }
}
