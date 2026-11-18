package org.gettserver.gettcore.nick.client;

import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/**
 * The nicknames this client was told about. Used for the name above a player's head (the client builds that name
 * itself) and wherever a screen lists players. Client only.
 */
public final class ClientNicknames {
    private static Map<UUID, String> nicknames = Map.of();

    private ClientNicknames() {}

    public static void set(Map<UUID, String> map) {
        nicknames = map;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null) for (Player p : mc.level.players()) p.refreshDisplayName();
    }

    /** Nickname, else {@code fallback}. */
    public static String nameOf(UUID id, String fallback) {
        String nick = nicknames.get(id);
        return nick != null ? nick : fallback;
    }

    /** Client-side players only: the server builds its own names (chat, tab list). */
    public static void onNameFormat(PlayerEvent.NameFormat event) {
        if (!event.getEntity().level().isClientSide) return;
        String nick = nicknames.get(event.getEntity().getUUID());
        if (nick != null) event.setDisplayname(Component.literal(nick));
    }

    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        nicknames = Map.of();
    }
}
