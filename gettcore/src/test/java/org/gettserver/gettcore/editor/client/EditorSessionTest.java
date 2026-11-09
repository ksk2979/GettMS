package org.gettserver.gettcore.editor.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 편집기 페이지 생존 신호: 닫으면 곧바로(잠깐 유예), 신호가 끊기면 60초 뒤 조용히 끝내고, 저장 안 한 변경이 있을 때만 알린다. */
class EditorSessionTest {
    private static long s(double seconds) {
        return (long) (seconds * TimeUnit.SECONDS.toNanos(1));
    }

    @Test
    void noHeartbeatYetMeansUnknownAndNothingHappens() {
        EditorSession es = new EditorSession();
        assertFalse(es.seen());
        assertTrue(es.check(s(3600)).isEmpty());
    }

    @Test
    void closedCleanPageEndsSilentlyAfterTheShortGrace() {
        EditorSession es = new EditorSession();
        es.heartbeat("a", false, false, s(0));
        es.close("a", false, s(10));
        assertTrue(es.check(s(11)).isEmpty(), "grace: a reload sends a heartbeat right away");
        Optional<EditorSession.End> end = es.check(s(10) + EditorSession.CLOSE_GRACE.toNanos());
        assertTrue(end.isPresent());
        assertFalse(end.get().unsaved());
    }

    @Test
    void closedDirtyPageReportsUnsaved() {
        EditorSession es = new EditorSession();
        es.heartbeat("a", true, false, s(0));
        es.close("a", true, s(1));
        assertTrue(es.check(s(30)).orElseThrow().unsaved());
    }

    @Test
    void reloadKeepsTheSession() {
        EditorSession es = new EditorSession();
        es.heartbeat("a", false, false, s(0));
        es.close("a", false, s(5));
        es.heartbeat("b", false, false, s(6));
        assertTrue(es.check(s(30)).isEmpty());
    }

    @Test
    void anotherOpenTabKeepsTheSession() {
        EditorSession es = new EditorSession();
        es.heartbeat("a", false, false, s(0));
        es.heartbeat("b", false, false, s(1));
        es.close("a", true, s(2));
        es.heartbeat("b", false, false, s(16));
        assertTrue(es.check(s(20)).isEmpty());
        es.close("b", false, s(25));
        Optional<EditorSession.End> end = es.check(s(40));
        assertTrue(end.isPresent());
        assertFalse(end.get().unsaved(), "the last page closed was saved");
    }

    @Test
    void lostHeartbeatEndsAfterTheTimeout() {
        EditorSession es = new EditorSession();
        es.heartbeat("a", true, false, s(0));
        assertTrue(es.check(s(59)).isEmpty());
        assertTrue(es.check(EditorSession.HEARTBEAT_TIMEOUT.toNanos()).orElseThrow().unsaved());
    }

    @Test
    void hiddenTabGetsTheLongTimeout() {
        // Browsers slow timers in background tabs to about once a minute.
        EditorSession es = new EditorSession();
        es.heartbeat("a", false, true, s(0));
        assertTrue(es.check(s(600)).isEmpty());
        assertTrue(es.check(EditorSession.HIDDEN_TIMEOUT.toNanos()).isPresent());
    }

    @Test
    void parsesReportsAndRejectsJunk() {
        EditorSession.Report r = EditorSession.parse("{\"page\":\"p-1_A\",\"dirty\":true,\"hidden\":true}").orElseThrow();
        assertEquals("p-1_A", r.page());
        assertTrue(r.dirty() && r.hidden());
        assertTrue(EditorSession.parse("{\"page\":\"\"}").isEmpty());
        assertTrue(EditorSession.parse("{\"page\":\"a b\"}").isEmpty());
        assertTrue(EditorSession.parse("not json").isEmpty());
        assertTrue(EditorSession.parse("{\"page\":\"" + "x".repeat(65) + "\"}").isEmpty());
        assertFalse(EditorSession.parse("{\"page\":\"a\"}").orElseThrow().dirty());
    }
}
