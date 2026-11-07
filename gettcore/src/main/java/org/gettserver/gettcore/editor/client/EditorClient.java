package org.gettserver.gettcore.editor.client;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.event.GameShuttingDownEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.editor.ChunkAssembler;
import org.gettserver.gettcore.editor.Chunks;
import org.gettserver.gettcore.network.EditorDataPayload;
import org.gettserver.gettcore.network.EditorSaveChunkPayload;

/**
 * Client side of the web editor: assembles snapshots/results from the server, runs the local HTTP server,
 * opens the browser and sends saves back. Client only; referenced from the client-bound payload handler.
 *
 * <p>The HTTP server is stopped on logout, after 30 minutes without requests, and on game shutdown. A new
 * snapshot while it runs only replaces the data, so an open browser tab keeps working.
 */
@EventBusSubscriber(modid = GettCore.MOD_ID, value = Dist.CLIENT)
public final class EditorClient {
    static final Duration IDLE_LIMIT = Duration.ofMinutes(30);
    static final int IDLE_CHECK_TICKS = 20 * 60;
    static final Duration ICON_WAIT = Duration.ofSeconds(5);
    /** Just past the HTTP save timeout, so a retry after a 504 is not refused as "still waiting". */
    static final Duration SAVE_EXPIRY = EditorHttpServer.DEFAULT_SAVE_TIMEOUT.plusSeconds(1);

    private static final Object LOCK = new Object();
    private static final AtomicInteger SAVE_SESSIONS = new AtomicInteger();
    private static final PendingSave PENDING = new PendingSave();
    private static final EditorBackend BACKEND = new Backend();

    // Client thread only.
    private static ChunkAssembler snapshotChunks = new ChunkAssembler();
    private static ChunkAssembler resultChunks = new ChunkAssembler();
    private static int tickCounter;

    // Guarded by LOCK.
    private static EditorHttpServer server;
    private static boolean shutdownHookAdded;

    private static volatile String snapshot = "{}";

    private EditorClient() {}

    /** Called on the client thread by the payload handler. */
    public static void onData(EditorDataPayload p) {
        boolean isSnapshot = p.kind() == EditorDataPayload.Kind.SNAPSHOT;
        Optional<String> done;
        try {
            done = (isSnapshot ? snapshotChunks : resultChunks).accept(p.sessionId(), p.index(), p.total(), p.data());
        } catch (IllegalArgumentException e) {
            GettCore.LOGGER.warn("editor {} chunk rejected: {}", p.kind(), e.getMessage());
            if (isSnapshot) {
                snapshotChunks = new ChunkAssembler();
            } else {
                resultChunks = new ChunkAssembler();
                PENDING.complete(EditorResultJson.failure("save result could not be read: " + e.getMessage()));
            }
            return;
        }
        if (done.isEmpty()) return;
        if (isSnapshot) onSnapshot(done.get());
        else onResult(done.get());
    }

    /** Advancement titles arrive as translation keys; show them in the client's language. */
    private static String translate(String key) {
        return I18n.exists(key) ? I18n.get(key) : key;
    }

    private static void onSnapshot(String json) {
        snapshot = EditorResultJson.localizeSnapshot(json, EditorClient::translate);
        Minecraft mc = Minecraft.getInstance();
        EditorHttpServer s;
        try {
            synchronized (LOCK) {
                if (server == null) {
                    server = EditorHttpServer.start(BACKEND);
                    addShutdownHookOnce();
                    GettCore.LOGGER.info("editor HTTP server started on port {}", server.port());
                }
                s = server;
            }
        } catch (RuntimeException e) {
            GettCore.LOGGER.error("editor HTTP server could not start", e);
            mc.gui.getChat().addMessage(Component.translatable("gettcore.msg.editor_server_failed")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        String url = s.url();
        Util.getPlatform().openUri(url);
        Component link = Component.literal(url).withStyle(style -> style
                .withClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, url))
                .withUnderlined(true)
                .withColor(ChatFormatting.AQUA));
        mc.gui.getChat().addMessage(Component.translatable("gettcore.msg.editor_link", link));
    }

    private static void onResult(String raw) {
        String json = EditorResultJson.localizeResult(raw, EditorClient::translate);
        EditorResultJson.snapshotOf(json).ifPresent(s -> snapshot = s);
        if (!PENDING.complete(json)) GettCore.LOGGER.info("editor save result arrived with no save waiting");
    }

    private static void addShutdownHookOnce() {
        if (shutdownHookAdded) return;
        shutdownHookAdded = true;
        Runtime.getRuntime().addShutdownHook(new Thread(() -> stopServer("JVM shutdown"), "gett-editor-shutdown"));
    }

    /**
     * Stops the HTTP server (if running) and answers a waiting save with a failure result (not an exception, so the
     * HTTP handler neither logs an error nor answers 500). Safe from any thread, idempotent.
     */
    static void stopServer(String reason) {
        EditorHttpServer s;
        synchronized (LOCK) {
            s = server;
            server = null;
        }
        if (PENDING.complete(EditorResultJson.failure(I18n.get("gettcore.msg.editor_closed")))) {
            GettCore.LOGGER.info("editor save still waiting was answered as failed ({})", reason);
        }
        if (s != null) {
            GettCore.LOGGER.info("editor HTTP server stopping ({})", reason);
            s.stop();
        }
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        stopServer("logout");
        snapshot = "{}";
        snapshotChunks = new ChunkAssembler();
        resultChunks = new ChunkAssembler();
        ClientCatalog.invalidate();
        IconRenderer.clear();
    }

    @SubscribeEvent
    public static void onShutdown(GameShuttingDownEvent event) {
        stopServer("game shutdown");
    }

    @SubscribeEvent
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        if (event.getUpdateCause() == TagsUpdatedEvent.UpdateCause.CLIENT_PACKET_RECEIVED) ClientCatalog.invalidate();
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        IconRenderer.processQueue();
        if (++tickCounter < IDLE_CHECK_TICKS) return;
        tickCounter = 0;
        boolean idle;
        synchronized (LOCK) {
            idle = server != null && server.idleFor(IDLE_LIMIT);
        }
        if (idle) {
            stopServer("idle");
            Minecraft.getInstance().gui.getChat().addMessage(Component.translatable("gettcore.msg.editor_idle_closed")
                    .withStyle(ChatFormatting.GRAY));
        }
    }

    private static final class Backend implements EditorBackend {
        @Override
        public String snapshotJson() {
            return snapshot;
        }

        @Override
        public String catalogJson() {
            return ClientCatalog.json();
        }

        @Override
        public Optional<byte[]> iconPng(String namespace, String path) {
            ResourceLocation id = ResourceLocation.tryBuild(namespace, path);
            if (id == null) return Optional.empty();
            try {
                return IconRenderer.png(id).get(ICON_WAIT.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                return Optional.empty();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return Optional.empty();
            } catch (Exception e) {
                GettCore.LOGGER.warn("editor icon {} failed", id, e);
                return Optional.empty();
            }
        }

        @Override
        public CompletableFuture<String> save(String bundleJson) {
            Optional<CompletableFuture<String>> begun = PENDING.begin(SAVE_EXPIRY);
            if (begun.isEmpty()) {
                return CompletableFuture.completedFuture(
                        EditorResultJson.failure("another save is still waiting for the server"));
            }
            CompletableFuture<String> f = begun.get();
            List<byte[]> chunks;
            try {
                chunks = Chunks.split(bundleJson);
            } catch (IllegalArgumentException e) {
                PENDING.finish(f, EditorResultJson.failure("save is too large: " + e.getMessage()));
                return f;
            }
            int session = SAVE_SESSIONS.incrementAndGet();
            Minecraft.getInstance().execute(() -> {
                if (Minecraft.getInstance().getConnection() == null) {
                    PENDING.finish(f, EditorResultJson.failure("not connected to a server"));
                    return;
                }
                for (int i = 0; i < chunks.size(); i++) {
                    PacketDistributor.sendToServer(new EditorSaveChunkPayload(session, i, chunks.size(), chunks.get(i)));
                }
            });
            return f;
        }
    }
}
