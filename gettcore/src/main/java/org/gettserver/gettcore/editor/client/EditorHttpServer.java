package org.gettserver.gettcore.editor.client;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Loopback-only HTTP server for the web editor. Pure JDK; data comes from an {@link EditorBackend}.
 *
 * <p>{@link #stop()} is mandatory: the JDK's HttpServer dispatcher thread is not a daemon thread, so a
 * server that is never stopped can keep the JVM alive. Only the request worker threads are daemons.
 */
public final class EditorHttpServer {
    static final int MAX_SAVE_BYTES = 16 * 1024 * 1024;
    static final Duration DEFAULT_SAVE_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BEACON_BYTES = 1024;

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
    private static final Pattern FILE = Pattern.compile("[A-Za-z0-9._-]+");
    private static final Pattern ICON_NS = Pattern.compile("[a-z0-9_.-]+");
    private static final Pattern ICON_PATH = Pattern.compile("[a-z0-9_./-]+");
    private static final Logger LOG = LoggerFactory.getLogger("gettcore");
    private static final String JSON = "application/json; charset=utf-8";

    private final EditorBackend backend;
    private final Duration saveTimeout;
    private final HttpServer http;
    private final ExecutorService pool;
    private final String token;
    private volatile long lastAuthorizedNanos = System.nanoTime();
    private final EditorSession session = new EditorSession();

    private EditorHttpServer(EditorBackend backend, Duration saveTimeout) throws IOException {
        this.backend = backend;
        this.saveTimeout = saveTimeout;
        this.token = newToken();
        AtomicInteger n = new AtomicInteger();
        this.pool = Executors.newFixedThreadPool(4, r -> {
            Thread t = new Thread(r, "gett-editor-http-" + n.incrementAndGet());
            t.setDaemon(true);
            return t;
        });
        this.http = HttpServer.create(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), 0), 0);
        this.http.setExecutor(pool);
        this.http.createContext("/", this::handle);
        this.http.start();
    }

    public static EditorHttpServer start(EditorBackend backend) {
        return start(backend, DEFAULT_SAVE_TIMEOUT);
    }

    public static EditorHttpServer start(EditorBackend backend, Duration saveTimeout) {
        try {
            return new EditorHttpServer(backend, saveTimeout);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start editor HTTP server", e);
        }
    }

    public int port() { return http.getAddress().getPort(); }

    public String token() { return token; }

    /** Open pages, fed by {@code /api/heartbeat} and the {@code /api/close} beacon. */
    public EditorSession session() { return session; }

    public String url() { return "http://127.0.0.1:" + port() + "/?t=" + token; }

    public void stop() {
        http.stop(0);
        pool.shutdownNow();
    }

    /** True if no authorized request arrived within the given duration. */
    public boolean idleFor(Duration d) {
        return System.nanoTime() - lastAuthorizedNanos >= d.toNanos();
    }

    public static boolean authorized(String host, int port, String expectedToken, String givenToken) {
        if (host == null || givenToken == null || expectedToken == null) return false;
        boolean hostOk = host.equals("127.0.0.1:" + port) || host.equals("localhost:" + port);
        boolean tokenOk = MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8), givenToken.getBytes(StandardCharsets.UTF_8));
        return hostOk & tokenOk;
    }

    private static String newToken() {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(32);
        for (int i = 0; i < 32; i++) sb.append(ALPHABET.charAt(rnd.nextInt(ALPHABET.length())));
        return sb.toString();
    }

    private static String queryToken(String rawQuery) {
        if (rawQuery == null) return null;
        for (String part : rawQuery.split("&")) {
            if (part.startsWith("t=")) return part.substring(2);
        }
        return null;
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            ex.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            ex.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            String given = ex.getRequestHeaders().getFirst("X-Gett-Token");
            if (given == null) given = queryToken(ex.getRequestURI().getRawQuery());
            if (!authorized(ex.getRequestHeaders().getFirst("Host"), port(), token, given)) {
                send(ex, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
                return;
            }
            lastAuthorizedNanos = System.nanoTime();
            route(ex, ex.getRequestMethod(), ex.getRequestURI().getPath());
        } catch (RuntimeException e) {
            LOG.error("Editor HTTP request failed: {}", ex.getRequestURI().getPath(), e);
            sendError500(ex);
        } finally {
            ex.close();
        }
    }

    private void route(HttpExchange ex, String method, String path) throws IOException {
        boolean get = method.equals("GET");
        if (path.equals("/api/heartbeat") || path.equals("/api/close")) {
            if (!method.equals("POST")) { send(ex, 405, "text/plain; charset=utf-8", new byte[0]); return; }
            pageReport(ex, path.equals("/api/close"));
        } else if (path.equals("/api/save")) {
            if (!method.equals("POST")) { send(ex, 405, "text/plain; charset=utf-8", new byte[0]); return; }
            save(ex);
        } else if (!get) {
            send(ex, 405, "text/plain; charset=utf-8", new byte[0]);
        } else if (path.equals("/")) {
            sendResource(ex, "index.html");
        } else if (path.startsWith("/static/")) {
            sendResource(ex, path.substring("/static/".length()));
        } else if (path.equals("/api/snapshot")) {
            sendApi(ex, 200, backend.snapshotJson());
        } else if (path.equals("/api/catalog")) {
            sendApi(ex, 200, backend.catalogJson());
        } else if (path.startsWith("/icon/")) {
            icon(ex, path.substring("/icon/".length()));
        } else {
            send(ex, 404, "text/plain; charset=utf-8", new byte[0]);
        }
    }

    private void sendResource(HttpExchange ex, String file) throws IOException {
        if (file.contains("..") || file.contains("\\") || !FILE.matcher(file).matches()
                || file.matches("[.]+")) {
            send(ex, 403, "text/plain; charset=utf-8", "forbidden".getBytes(StandardCharsets.UTF_8));
            return;
        }
        byte[] data;
        try (InputStream in = EditorHttpServer.class.getResourceAsStream("/gettcore_editor/" + file)) {
            if (in == null) { send(ex, 404, "text/plain; charset=utf-8", new byte[0]); return; }
            data = in.readAllBytes();
        }
        send(ex, 200, contentType(file), data);
    }

    private static String contentType(String file) {
        String f = file.toLowerCase(java.util.Locale.ROOT);
        if (f.endsWith(".html")) return "text/html; charset=utf-8";
        if (f.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (f.endsWith(".css")) return "text/css; charset=utf-8";
        if (f.endsWith(".png")) return "image/png";
        if (f.endsWith(".json")) return JSON;
        if (f.endsWith(".svg")) return "image/svg+xml; charset=utf-8";
        return "application/octet-stream";
    }

    private void icon(HttpExchange ex, String rest) throws IOException {
        int slash = rest.indexOf('/');
        if (slash <= 0 || !rest.endsWith(".png")) { send(ex, 404, "text/plain; charset=utf-8", new byte[0]); return; }
        String ns = rest.substring(0, slash);
        String p = rest.substring(slash + 1, rest.length() - 4);
        if (!ICON_NS.matcher(ns).matches() || !ICON_PATH.matcher(p).matches() || badSegment(ns) || badSegments(p)) {
            send(ex, 404, "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        Optional<byte[]> png = backend.iconPng(ns, p);
        if (png.isPresent()) send(ex, 200, "image/png", png.get());
        else send(ex, 404, "text/plain; charset=utf-8", new byte[0]);
    }

    private void save(HttpExchange ex) throws IOException {
        String cl = ex.getRequestHeaders().getFirst("Content-Length");
        boolean tooBig = false;
        try {
            if (cl != null && Long.parseLong(cl) > MAX_SAVE_BYTES) tooBig = true;
        } catch (NumberFormatException e) {
            send(ex, 400, "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        byte[] body = null;
        if (!tooBig) {
            body = ex.getRequestBody().readNBytes(MAX_SAVE_BYTES + 1);
            tooBig = body.length > MAX_SAVE_BYTES;
        }
        if (tooBig) {
            // 응답 전에 보낸 본문을 (상한까지) 읽어 버린다. 안 읽고 닫으면 아직 보내는 중인 브라우저 쪽 연결이 끊겨
            // 413 대신 "연결 재설정" 오류로 보인다.
            byte[] sink = new byte[64 * 1024];
            long left = 4L * MAX_SAVE_BYTES;
            int n;
            while (left > 0 && (n = ex.getRequestBody().read(sink, 0, (int) Math.min(sink.length, left))) > 0) left -= n;
            ex.getResponseHeaders().set("Connection", "close");
            sendApi(ex, 413, "{\"error\":\"too large\"}");
            return;
        }
        try {
            String result = backend.save(new String(body, StandardCharsets.UTF_8))
                    .get(saveTimeout.toMillis(), TimeUnit.MILLISECONDS);
            sendApi(ex, 200, result);
        } catch (TimeoutException e) {
            sendApi(ex, 504, "{\"error\":\"timeout\"}");
        } catch (ExecutionException e) {
            LOG.error("Editor save failed", e.getCause() != null ? e.getCause() : e);
            sendError500(ex);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            sendApi(ex, 500, "{\"error\":\"interrupted\"}");
        }
    }

    /**
     * Heartbeat (fetch, token header) or close beacon (navigator.sendBeacon: no custom headers, so the token comes in
     * the query like the page URL; body sent as text/plain). Same host + token check as every request.
     */
    private void pageReport(HttpExchange ex, boolean close) throws IOException {
        byte[] body = ex.getRequestBody().readNBytes(MAX_BEACON_BYTES + 1);
        Optional<EditorSession.Report> r = body.length > MAX_BEACON_BYTES ? Optional.empty()
                : EditorSession.parse(new String(body, StandardCharsets.UTF_8));
        if (r.isEmpty()) {
            send(ex, 400, "text/plain; charset=utf-8", new byte[0]);
            return;
        }
        long now = System.nanoTime();
        if (close) session.close(r.get().page(), r.get().dirty(), now);
        else session.heartbeat(r.get().page(), r.get().dirty(), r.get().hidden(), now);
        send(ex, 204, "text/plain; charset=utf-8", new byte[0]);
    }

    private static boolean badSegment(String seg) {
        return seg.isEmpty() || seg.equals(".") || seg.equals("..");
    }

    private static boolean badSegments(String path) {
        for (String seg : path.split("/", -1)) if (badSegment(seg)) return true;
        return false;
    }

    private void sendError500(HttpExchange ex) {
        try {
            sendApi(ex, 500, "{\"error\":\"internal error\"}");
        } catch (IOException | RuntimeException e) {
            LOG.debug("Could not send 500 (response probably already started)", e);
        }
    }

    private void sendApi(HttpExchange ex, int status, String json) throws IOException {
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        send(ex, status, JSON, json.getBytes(StandardCharsets.UTF_8));
    }

    private void send(HttpExchange ex, int status, String type, byte[] data) throws IOException {
        ex.getResponseHeaders().set("Content-Type", type);
        if (data.length == 0) {
            ex.sendResponseHeaders(status, -1);
            return;
        }
        ex.sendResponseHeaders(status, data.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(data);
        }
    }
}
