package org.gettserver.gettcore.editor.client;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class EditorHttpServerTest {
    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G'};

    EditorHttpServer server;
    HttpClient http = HttpClient.newHttpClient();
    volatile CompletableFuture<String> saveFuture = CompletableFuture.completedFuture("{\"ok\":true}");
    volatile String lastSaveBody;

    final EditorBackend backend = new EditorBackend() {
        public String snapshotJson() { return "{\"shops\":{}}"; }
        public String catalogJson() { return "{\"items\":[]}"; }
        public Optional<byte[]> iconPng(String ns, String path) {
            return ns.equals("minecraft") && path.equals("stone") ? Optional.of(PNG) : Optional.empty();
        }
        public CompletableFuture<String> save(String bundleJson) {
            lastSaveBody = bundleJson;
            return saveFuture;
        }
    };

    @BeforeEach
    void up() { server = EditorHttpServer.start(backend, Duration.ofMillis(300)); }

    @AfterEach
    void down() { server.stop(); }

    String base() { return "http://127.0.0.1:" + server.port(); }

    HttpResponse<String> get(String path, String headerToken) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base() + path));
        if (headerToken != null) b.header("X-Gett-Token", headerToken);
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void rejectsMissingToken() throws Exception {
        assertEquals(403, get("/", null).statusCode());
        assertEquals(403, get("/api/snapshot", null).statusCode());
        assertEquals(403, get("/api/snapshot", "wrong").statusCode());
        assertEquals(403, get("/static/index.html", null).statusCode());
    }

    @Test
    void rejectsForeignHost() throws Exception {
        String req = "GET /api/snapshot HTTP/1.1\r\nHost: evil.com\r\nX-Gett-Token: " + server.token()
                + "\r\nConnection: close\r\n\r\n";
        try (Socket s = new Socket("127.0.0.1", server.port())) {
            s.getOutputStream().write(req.getBytes(StandardCharsets.US_ASCII));
            s.getOutputStream().flush();
            String resp = new String(s.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(resp.startsWith("HTTP/1.1 403"), resp);
        }
    }

    @Test
    void authorizedChecksHostAndToken() {
        assertTrue(EditorHttpServer.authorized("127.0.0.1:5", 5, "abc", "abc"));
        assertTrue(EditorHttpServer.authorized("localhost:5", 5, "abc", "abc"));
        assertFalse(EditorHttpServer.authorized("localhost:6", 5, "abc", "abc"));
        assertFalse(EditorHttpServer.authorized("evil.com", 5, "abc", "abc"));
        assertFalse(EditorHttpServer.authorized(null, 5, "abc", "abc"));
        assertFalse(EditorHttpServer.authorized("127.0.0.1:5", 5, "abc", "abd"));
        assertFalse(EditorHttpServer.authorized("127.0.0.1:5", 5, "abc", null));
    }

    @Test
    void tokenShapeAndUrl() {
        assertTrue(server.token().matches("[A-Za-z0-9]{32}"));
        assertEquals(base() + "/?t=" + server.token(), server.url());
    }

    @Test
    void servesSnapshotWithToken() throws Exception {
        var r = get("/api/snapshot", server.token());
        assertEquals(200, r.statusCode());
        assertEquals("{\"shops\":{}}", r.body());
        assertEquals("application/json; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(""));
        assertEquals("nosniff", r.headers().firstValue("X-Content-Type-Options").orElse(""));
        var q = get("/api/catalog?t=" + server.token(), null);
        assertEquals(200, q.statusCode());
        assertEquals("{\"items\":[]}", q.body());
    }

    @Test
    void servesIndexAndStatic() throws Exception {
        var r = get("/?t=" + server.token(), null);
        assertEquals(200, r.statusCode());
        assertEquals("text/html; charset=utf-8", r.headers().firstValue("Content-Type").orElse(""));
        assertTrue(r.body().contains("편집기 준비 중"));
        assertEquals(200, get("/static/index.html?t=" + server.token(), null).statusCode());
        assertEquals(404, get("/static/nope.js?t=" + server.token(), null).statusCode());
    }

    @Test
    void iconReturns404WhenAbsent() throws Exception {
        var miss = http.send(HttpRequest.newBuilder(URI.create(
                base() + "/icon/minecraft/dirt.png?t=" + server.token())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(404, miss.statusCode());
        var hit = http.send(HttpRequest.newBuilder(URI.create(
                base() + "/icon/minecraft/stone.png?t=" + server.token())).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, hit.statusCode());
        assertArrayEquals(PNG, hit.body());
        assertEquals("image/png", hit.headers().firstValue("Content-Type").orElse(""));
    }

    HttpResponse<String> post(String body) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(base() + "/api/save"))
                .header("X-Gett-Token", server.token())
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void saveReturnsBackendResult() throws Exception {
        var r = post("{\"a\":1}");
        assertEquals(200, r.statusCode());
        assertEquals("{\"ok\":true}", r.body());
        assertEquals("{\"a\":1}", lastSaveBody);
    }

    @Test
    void saveRequiresToken() throws Exception {
        var r = http.send(HttpRequest.newBuilder(URI.create(base() + "/api/save"))
                .POST(HttpRequest.BodyPublishers.ofString("x")).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, r.statusCode());
        assertNull(lastSaveBody);
    }

    @Test
    void saveTimesOutWith504() throws Exception {
        saveFuture = new CompletableFuture<>();
        assertEquals(504, post("{}").statusCode());
    }

    @Test
    void saveTooLargeIs413() throws Exception {
        String big = "x".repeat(16 * 1024 * 1024 + 1);
        assertEquals(413, post(big).statusCode());
        assertNull(lastSaveBody);
    }

    @Test
    void pathTraversalRejected() throws Exception {
        String t = "?t=" + server.token();
        assertEquals(403, get("/static/..%2Fsecret" + t, null).statusCode());
        assertEquals(403, get("/static/%2e%2e/x" + t, null).statusCode());
        assertEquals(403, get("/static/a%5Cb" + t, null).statusCode());
        assertEquals(403, get("/static/a%20b" + t, null).statusCode());
        assertEquals(403, get("/static/.." + t, null).statusCode());
    }

    @Test
    void idleForTracksAuthorizedRequests() throws Exception {
        assertFalse(server.idleFor(Duration.ofHours(1)));
        Thread.sleep(60);
        assertTrue(server.idleFor(Duration.ofMillis(30)));
        get("/api/snapshot", "wrong");
        assertTrue(server.idleFor(Duration.ofMillis(30)));
        get("/api/snapshot", server.token());
        assertFalse(server.idleFor(Duration.ofMillis(30)));
    }
}
