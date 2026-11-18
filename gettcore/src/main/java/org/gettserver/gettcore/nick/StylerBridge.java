package org.gettserver.gettcore.nick;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.gettserver.gettcore.GettCore;

/**
 * PlayerName Styler (optional, server side) draws names itself: it cancels the chat event and sends its own line
 * from a format string, and it formats the tab list and the name above the head. So with that mod installed our
 * nickname is handed to it (it then shows everywhere it draws names), and the party tag is offered as the
 * placeholder {@code %party%}, which is also put into its chat format when missing. Without the mod nothing here runs and the name events are used.
 *
 * <p>Reflection only: the mod is not a compile dependency. A mismatch (another version) logs once and turns the
 * bridge off; the nickname itself is still stored and used by our own screens and commands.
 */
public final class StylerBridge {
    public static final String MOD_ID = "playernamestyler";
    public static final String PARTY_PLACEHOLDER = "%party%";
    private static final String PKG = "sierra.thing.playernamestyler.";

    private static boolean broken;

    private StylerBridge() {}

    /** Whether that mod draws the names (then our own name events stay out of the way). */
    public static boolean active() {
        return !broken && ModList.get().isLoaded(MOD_ID);
    }

    /** Hands the nickname to the mod ({@code null} = none) and lets it redraw the player's names. */
    public static void apply(ServerPlayer player, String nickname) {
        if (!active()) return;
        try {
            Object manager = Class.forName(PKG + "PlayerNameStyler").getField("nicknameManager").get(null);
            if (manager == null) return;
            UUID id = player.getUUID();
            Class<?> events = Class.forName(PKG + "events.NicknameEvents");
            Object current = manager.getClass().getMethod("getNickname", UUID.class).invoke(manager, id);
            if (nickname == null) {
                if (current == null) return;
                manager.getClass().getMethod("removeNickname", UUID.class).invoke(manager, id);
                events.getMethod("clearNickname", ServerPlayer.class).invoke(null, player);
            } else {
                if (nickname.equals(current)) return;
                manager.getClass().getMethod("setNickname", UUID.class, String.class).invoke(manager, id, nickname);
                events.getMethod("updateNicknameFor", ServerPlayer.class).invoke(null, player);
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /**
     * Registers {@code %party%} for the mod's chat / tab / name formats: "[party name] " (gold) or "". The format
     * string in its config has to contain the placeholder for it to show.
     */
    public static void registerPartyPlaceholder(Function<ServerPlayer, String> partyName) {
        if (!active()) return;
        try {
            Class<?> resolver = Class.forName(PKG + "api.PlaceholderResolver");
            Class<?> context = Class.forName(PKG + "api.PlaceholderContext");
            Method getPlayer = context.getMethod("getPlayer");
            InvocationHandler handler = (proxy, method, args) -> {
                if (!method.getName().equals("resolve")) {
                    return switch (method.getName()) {
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> "gettcore party placeholder";
                    };
                }
                String name = partyName.apply((ServerPlayer) getPlayer.invoke(args[0]));
                if (name == null || name.isEmpty()) return "";
                // The mod parses colour codes and tags in the finished line: keep them out of the party name.
                return "&6[" + name.replaceAll("[&<>%]", "") + "]&r ";
            };
            Object proxy = Proxy.newProxyInstance(resolver.getClassLoader(), new Class<?>[] {resolver}, handler);
            Class.forName(PKG + "api.PlaceholderRegistry").getMethod("register", String.class, resolver)
                    .invoke(null, PARTY_PLACEHOLDER, proxy);
            addPartyToChatFormat();
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            fail(e);
        }
    }

    /**
     * Puts the placeholder into the chat format in use when it is not there, so the party tag shows without anyone
     * editing that config. Only the loaded value changes; the file is left as it is.
     */
    @SuppressWarnings("unchecked")
    private static void addPartyToChatFormat() throws ReflectiveOperationException {
        Object value = Class.forName(PKG + "data.PlayerNameStylerConfig").getField("CHAT_FORMAT").get(null);
        var chatFormat = (net.neoforged.neoforge.common.ModConfigSpec.ConfigValue<String>) value;
        String now = chatFormat.get();
        String wanted = withParty(now);
        if (wanted.equals(now)) {
            GettCore.LOGGER.info("gettcore: PlayerName Styler 연동 — 채팅 형식: {}", now);
            return;
        }
        chatFormat.set(wanted);
        GettCore.LOGGER.info("gettcore: PlayerName Styler 연동 — 채팅 형식에 {} 추가: {}", PARTY_PLACEHOLDER, wanted);
    }

    /**
     * The chat format with the party placeholder: unchanged when it already has one, otherwise put right before the
     * title (%prefix%) or, without that, before the name; a format with neither is left alone.
     */
    public static String withParty(String format) {
        if (format == null || format.contains(PARTY_PLACEHOLDER)) return format;
        for (String anchor : new String[] {"%prefix%", "%displayname%", "%username%"}) {
            int i = format.indexOf(anchor);
            if (i >= 0) return format.substring(0, i) + PARTY_PLACEHOLDER + format.substring(i);
        }
        return format;
    }

    private static void fail(Throwable e) {
        broken = true;
        GettCore.LOGGER.warn("gettcore: PlayerName Styler 연동을 끕니다 (이 버전과 맞지 않음)", e);
    }
}
