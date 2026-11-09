package org.gettserver.gettcore.editor;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.io.IOException;
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
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.balance.BalanceService;
import org.gettserver.gettcore.data.DataEvents;
import org.gettserver.gettcore.data.GettData;
import org.gettserver.gettcore.data.RegistryChecks;
import org.gettserver.gettcore.network.EditorDataPayload;
import org.gettserver.gettcore.network.EditorSaveChunkPayload;
import org.gettserver.gettcore.perm.Perms;
import org.gettserver.gettcore.pet.boss.BossPointData;
import org.gettserver.gettcore.quest.QuestService;

/** Server side of the web editor: sends snapshots, assembles and applies saves. Server thread only. */
@EventBusSubscriber(modid = GettCore.MOD_ID)
public final class EditorServerService {
    private static final AtomicInteger SESSIONS = new AtomicInteger();
    private static final Map<UUID, ChunkAssembler> ASSEMBLERS = new HashMap<>();

    private EditorServerService() {}

    /** Returns true when the snapshot was sent. */
    public static boolean open(ServerPlayer player) {
        if (!Perms.has(player, Perms.ADMIN)) {
            GettCore.LOGGER.warn("editor open denied for {}", player.getGameProfile().getName());
            return false;
        }
        try {
            String json = snapshotJson(player.server);
            send(player, EditorDataPayload.Kind.SNAPSHOT, json);
            return true;
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("editor open failed", e);
            player.sendSystemMessage(Component.translatable("gettcore.msg.editor_open_failed"));
            return false;
        }
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
            ASSEMBLERS.remove(player.getUUID());
            sendResult(player, false, List.of("upload rejected: " + e.getMessage()), List.of(), false);
            return;
        }
        if (text == null) return;

        MinecraftServer server = player.server;
        boolean saved = false;
        List<String> errors;
        List<String> warnings = List.of();
        try {
            EditorBundle bundle = EditorJson.decode(text);
            // Balance data pack (guns / spawns): checked before anything is written, applied after the bundle saved.
            JsonObject balance = BalanceService.desiredOf(text);
            List<String> balanceErrors = balance == null ? List.of() : BalanceService.validate(balance);
            if (!balanceErrors.isEmpty()) {
                sendResult(player, false, balanceErrors, List.of(), false);
                return;
            }
            GettData.SaveResult r = GettData.get().saveBundle(bundle, RegistryChecks.live());
            saved = r.saved();
            errors = r.errors();
            warnings = r.warnings();
            if (r.reload() != null && !r.reload().errors().isEmpty()) {
                // Saved files that fail to load again are not silently dropped from the editor's view.
                List<String> w = new ArrayList<>(warnings);
                r.reload().errors().forEach(e -> w.add("reload: " + e));
                warnings = w;
            }
            if (saved) {
                // Bosses of removed points are discarded by their own tick (BossBehavior.isOrphan).
                BossPointData points = BossPointData.get(server);
                for (String id : r.removedBossPoints()) points.remove(id);
                GettData.runReloadHooks();
                DataEvents.syncConfig(server);
                QuestService.refreshAll(server);
                if (balance != null) {
                    List<String> w = new ArrayList<>(warnings);
                    try {
                        w.addAll(BalanceService.apply(server, balance).messages());
                    } catch (IOException | RuntimeException e) {
                        GettCore.LOGGER.error("balance data pack save failed", e);
                        w.add("총·등장 데이터팩을 쓰지 못했습니다: " + e.getMessage());
                    }
                    warnings = w;
                }
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

        sendResult(player, saved, errors, warnings, saved);
    }

    private static void sendResult(ServerPlayer player, boolean saved, List<String> errors, List<String> warnings,
                                   boolean withSnapshot) {
        JsonObject res = new JsonObject();
        res.addProperty("saved", saved);
        res.add("errors", strings(errors));
        res.add("snapshot", JsonNull.INSTANCE);
        List<String> warn = warnings;
        String text;
        try {
            if (withSnapshot) {
                res.add("snapshot", JsonParser.parseString(
                        snapshotJson(player.server)));
            }
            res.add("warnings", strings(warn));
            text = res.toString();
            send(player, EditorDataPayload.Kind.RESULT, text);
            return;
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("editor result/snapshot could not be sent", e);
            warn = new ArrayList<>(warnings);
            warn.add("snapshot could not be sent: " + e.getMessage());
        }
        res.add("snapshot", JsonNull.INSTANCE);
        res.add("warnings", strings(warn));
        try {
            send(player, EditorDataPayload.Kind.RESULT, res.toString());
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("editor result could not be sent", e);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        ASSEMBLERS.clear();
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        ASSEMBLERS.remove(event.getEntity().getUUID());
    }

    /** Bundle + refs + the balance data pack sections (see BalanceService). */
    static String snapshotJson(MinecraftServer server) {
        return BalanceService.withSections(EditorJson.encode(GettData.get().rawBundle(), refs(server)), server);
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
