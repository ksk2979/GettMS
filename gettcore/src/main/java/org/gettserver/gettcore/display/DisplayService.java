package org.gettserver.gettcore.display;

import com.dreamdisplays.api.CommandResult;
import com.dreamdisplays.api.playback.model.PlaybackAction;
import com.dreamdisplays.api.playback.model.PlaybackMode;
import com.dreamdisplays.api.playback.model.Timeline;
import com.dreamdisplays.api.server.DreamDisplaysServer;
import com.dreamdisplays.api.server.ServerDisplay;
import com.dreamdisplays.platform.server.commands.subcommands.DisplayTargetResolutionKt;
import com.dreamdisplays.platform.server.commands.subcommands.VideoCommandKt;
import com.dreamdisplays.platform.server.datatypes.display.DisplayData;
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData;
import com.dreamdisplays.platform.server.managers.DisplayManager;
import com.dreamdisplays.platform.server.playback.TimelineManager;
import com.dreamdisplays.platform.server.utils.net.VanillaDisplayActions;
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.DisplayLoopSyncPayload;
import org.gettserver.gettcore.network.DisplayPersonalPayload;

/**
 * Server side of the Dream Displays helpers (written against Dream Displays 1.10.9; only loaded when that mod is):
 * clear a display's video, the per-display repeat switch, and the "only me" video. Uses the mod's internals, so every
 * entry point turns a link failure into {@link #unsupported}.
 */
public final class DisplayService {
    private static final int END_CHECK_TICKS = 10;
    private static boolean broken;
    private static Map<UUID, Timeline> timelines;

    private DisplayService() {}

    public static void install() {
        NeoForge.EVENT_BUS.addListener(DisplayService::onServerTick);
    }

    /** The display the player looks at; the mod itself tells the player when there is none. */
    public static DisplayData target(ServerPlayer player) {
        return DisplayTargetResolutionKt.resolveVanillaDisplayTarget(player, "this");
    }

    public static DisplayData byId(UUID id) {
        return DisplayManager.getDisplayData(id);
    }

    private static boolean canManage(ServerPlayer player, DisplayData display) {
        return player.getUUID().equals(display.getOwnerId()) || VanillaDisplayActions.INSTANCE.isAdmin(player);
    }

    /** Back to the blank state of a new display, for everyone. Owner or staff. */
    public static boolean clear(ServerPlayer player, DisplayData display) {
        if (!canManage(player, display)) return fail(player, "gettcore.display.no_permission");
        if (display.getUrl() == null || display.getUrl().isEmpty()) return fail(player, "gettcore.display.already_blank");
        ServerDisplay api = DreamDisplaysServer.display(display.getId());
        if (api == null || api.setUrl("", "") != CommandResult.OK) return fail(player, "gettcore.display.failed");
        // A client keeps playing when the address just becomes empty: drop its screen; the next info packet (the mod
        // resends every second) builds it again, blank.
        MinecraftServer server = player.server;
        VanillaPacketUtil.INSTANCE.sendDelete(server.getPlayerList().getPlayers(), display.getId());
        if (display instanceof VanillaDisplayData vanilla) {
            DisplayManager.INSTANCE.sendUpdate(vanilla, DisplayManager.INSTANCE.getReceivers(vanilla, server));
        }
        player.sendSystemMessage(Component.translatable("gettcore.display.cleared"));
        return true;
    }

    /** {@code on} null toggles. Owner or staff. */
    public static boolean setLoop(ServerPlayer player, DisplayData display, Boolean on) {
        if (!canManage(player, display)) return fail(player, "gettcore.display.no_permission");
        DisplayLoopData data = DisplayLoopData.get(player.server);
        boolean value = on != null ? on : !data.loops(display.getId());
        data.set(display.getId(), value);
        PacketDistributor.sendToAllPlayers(new DisplayLoopSyncPayload(data.all()));
        player.sendSystemMessage(Component.translatable(value ? "gettcore.display.loop_on" : "gettcore.display.loop_off"));
        return true;
    }

    /** A video only this player sees on the display ({@code rawUrl} blank: back to the shared video). Anyone. */
    public static boolean personal(ServerPlayer player, DisplayData display, String rawUrl) {
        String url = DisplayRules.cleanUrl(rawUrl);
        if (!url.isEmpty()) {
            try {
                // the mod's own check of /display video; a synthetic accessor, which Java source cannot call
                java.lang.reflect.Method m = VideoCommandKt.class.getMethod("access$canonicalUrl", String.class);
                url = (String) m.invoke(null, url);
            } catch (ReflectiveOperationException e) {
                unsupported(player, e);
                return false;
            }
            if (url == null || url.isEmpty()) return fail(player, "gettcore.display.invalid_url");
        }
        PacketDistributor.sendToPlayer(player, new DisplayPersonalPayload(display.getId(), url));
        return true;
    }

    /** From the buttons in the display menu. */
    public static void handleAction(ServerPlayer player, UUID id, int action, String url) {
        try {
            DisplayData display = byId(id);
            if (display == null) {
                fail(player, "gettcore.display.failed");
                return;
            }
            switch (action) {
                case DisplayRules.ACTION_CLEAR -> clear(player, display);
                case DisplayRules.ACTION_LOOP_TOGGLE -> setLoop(player, display, null);
                case DisplayRules.ACTION_PERSONAL -> personal(player, display, url);
                default -> { }
            }
        } catch (LinkageError e) {
            unsupported(player, e);
        }
    }

    public static void unsupported(ServerPlayer player, Throwable e) {
        GettCore.LOGGER.warn("gettcore: Dream Displays 연동 실패 (호환되지 않는 버전?): {}", e.toString());
        player.sendSystemMessage(Component.translatable("gettcore.display.unsupported"));
    }

    private static boolean fail(ServerPlayer player, String key) {
        player.sendSystemMessage(Component.translatable(key));
        return false;
    }

    /**
     * Shared (synced, broadcast) displays run on a server timeline that always wraps round: a display that does not
     * repeat is paused just before its end. Local displays end on each client (see the client mixin).
     */
    private static void onServerTick(ServerTickEvent.Post event) {
        MinecraftServer server = event.getServer();
        if (broken || server.getTickCount() % END_CHECK_TICKS != 0) return;
        try {
            DisplayLoopData loops = DisplayLoopData.get(server);
            long now = System.currentTimeMillis();
            for (DisplayData display : List.copyOf(DisplayManager.INSTANCE.getDisplays())) {
                PlaybackMode mode = display.getMode();
                if (mode != PlaybackMode.SYNCED && mode != PlaybackMode.BROADCAST) continue;
                if (loops.loops(display.getId())) continue;
                Timeline t = timelines().get(display.getId());
                if (t == null) continue;
                if (DisplayRules.reachedEnd(t.getPositionMs(), t.getServerTimeMs(), t.getPaused(), t.getDurationMs(),
                        now, DisplayRules.END_MARGIN_MS)) {
                    TimelineManager.INSTANCE.applyScheduled(display, PlaybackAction.PAUSE);
                }
            }
        } catch (LinkageError | ReflectiveOperationException | RuntimeException e) {
            broken = true;
            GettCore.LOGGER.warn("gettcore: 화면 반복 설정(서버) 비활성 — Dream Displays 연동 실패: {}", e.toString());
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Timeline> timelines() throws ReflectiveOperationException {
        if (timelines == null) {
            Field f = TimelineManager.class.getDeclaredField("timelines");
            f.setAccessible(true);
            timelines = (Map<UUID, Timeline>) f.get(null);
        }
        return timelines;
    }
}
