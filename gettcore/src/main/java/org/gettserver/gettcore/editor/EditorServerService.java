package org.gettserver.gettcore.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.DataEvents;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.network.EditorDataPayload;
import org.gettserver.gettcore.network.EditorSaveChunkPayload;
import org.gettserver.gettcore.perm.Perms;

/** Server side of the web editor: sends snapshots, assembles and applies saves. Server thread only. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class EditorServerService {
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final Map<UUID, ChunkAssembler> ASSEMBLERS = new HashMap<>();

    private EditorServerService() {}

    public static void open(ServerPlayer player) {
        if (!Perms.has(player, Perms.ADMIN)) {
            GettCore.LOGGER.warn("editor open denied for {}", player.getGameProfile().getName());
            return;
        }
        String json = EditorJson.encode(GettData.get().rawBundle(), refs(player.server));
        send(player, EditorDataPayload.Kind.SNAPSHOT, json);
    }

    public static void onChunk(ServerPlayer player, EditorSaveChunkPayload p) {
        if (!Perms.has(player, Perms.ADMIN)) {
            GettCore.LOGGER.warn("editor save denied for {}", player.getGameProfile().getName());
            return;
        }
        String text;
        try {
            text = ASSEMBLERS.computeIfAbsent(player.getUUID(), k -> new ChunkAssembler())
                    .accept(p.sessionId(), p.index(), p.total(), p.data()).orElse(null);
        } catch (IllegalArgumentException e) {
            GettCore.LOGGER.warn("editor save chunk from {} rejected: {}", player.getGameProfile().getName(),
                    e.getMessage());
            return;
        }
        if (text == null) return;

        MinecraftServer server = player.server;
        boolean saved = false;
        List<String> errors;
        List<String> warnings = List.of();
        try {
            EditorBundle bundle = EditorJson.decode(text);
            GettData.SaveResult r = GettData.get().saveBundle(bundle, RegistryChecks.live());
            saved = r.saved();
            errors = r.errors();
            warnings = r.warnings();
            if (saved) {
                GettData.runReloadHooks();
                DataEvents.syncConfig(server);
            }
        } catch (IllegalArgumentException e) {
            GettCore.LOGGER.warn("editor save from {} invalid: {}", player.getGameProfile().getName(), e.getMessage());
            errors = List.of(String.valueOf(e.getMessage()));
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("editor save failed", e);
            errors = List.of("internal error: " + e);
        }
        GettCore.LOGGER.info("editor save by {}: saved={} errors={} warnings={}", player.getGameProfile().getName(),
                saved, errors.size(), warnings.size());

        JsonObject res = new JsonObject();
        res.addProperty("saved", saved);
        res.add("errors", strings(errors));
        res.add("warnings", strings(warnings));
        if (saved) {
            res.add("snapshot", JsonParser.parseString(
                    EditorJson.encode(GettData.get().rawBundle(), refs(server))));
        } else {
            res.add("snapshot", JsonNull.INSTANCE);
        }
        send(player, EditorDataPayload.Kind.RESULT, res.toString());
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ASSEMBLERS.remove(event.getEntity().getUUID());
    }

    static EditorRefs refs(MinecraftServer server) {
        List<EditorRefs.AdvRef> advs = new ArrayList<>();
        for (AdvancementHolder holder : server.getAdvancements().getAllAdvancements()) {
            holder.value().display().ifPresent(d -> advs.add(
                    new EditorRefs.AdvRef(holder.id().toString(), titleOf(d.getTitle()))));
        }
        advs.sort(Comparator.comparing(EditorRefs.AdvRef::id));
        EditorBundle raw = GettData.get().rawBundle();
        RegistryChecks live = RegistryChecks.live();
        return new EditorRefs(advs, MissingRefs.items(raw, live), MissingRefs.entities(raw, live));
    }

    private static String titleOf(Component title) {
        if (title.getContents() instanceof TranslatableContents t) return t.getKey();
        return title.getString();
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.forEach(a::add);
        return a;
    }

    private static void send(ServerPlayer player, EditorDataPayload.Kind kind, String text) {
        int session = SESSIONS.incrementAndGet();
        List<byte[]> chunks = Chunks.split(text);
        for (int i = 0; i < chunks.size(); i++) {
            PacketDistributor.sendToPlayer(player,
                    new EditorDataPayload(kind, session, i, chunks.size(), chunks.get(i)));
        }
    }
}
