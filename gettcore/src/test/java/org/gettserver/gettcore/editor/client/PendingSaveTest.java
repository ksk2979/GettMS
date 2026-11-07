package org.gettserver.gettcore.editor.client;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

class PendingSaveTest {
    static final Duration LONG = Duration.ofMinutes(1);

    @Test
    void onlyOneSaveAtATime() throws Exception {
        PendingSave p = new PendingSave();
        CompletableFuture<String> f = p.begin(LONG).orElseThrow();
        assertTrue(p.begin(LONG).isEmpty());
        assertTrue(p.complete("{\"saved\":true}"));
        assertEquals("{\"saved\":true}", f.get());
        assertTrue(p.begin(LONG).isPresent());
    }

    @Test
    void completeWithoutWaiterReturnsFalse() {
        assertFalse(new PendingSave().complete("{}"));
    }

    @Test
    void failAllCompletesExceptionallyAndFreesSlot() {
        PendingSave p = new PendingSave();
        CompletableFuture<String> f = p.begin(LONG).orElseThrow();
        p.failAll(new IllegalStateException("closed"));
        ExecutionException e = assertThrows(ExecutionException.class, f::get);
        assertInstanceOf(IllegalStateException.class, e.getCause());
        assertTrue(p.begin(LONG).isPresent());
    }

    @Test
    void finishAnswersLocally() throws Exception {
        PendingSave p = new PendingSave();
        CompletableFuture<String> f = p.begin(LONG).orElseThrow();
        p.finish(f, "local");
        assertEquals("local", f.get());
        assertFalse(p.complete("late"));
    }

    @Test
    void expiredSaveFreesSlot() throws Exception {
        PendingSave p = new PendingSave();
        CompletableFuture<String> f = p.begin(Duration.ofMillis(50)).orElseThrow();
        ExecutionException e = assertThrows(ExecutionException.class, () -> f.get(5, TimeUnit.SECONDS));
        assertInstanceOf(TimeoutException.class, e.getCause());
        assertTrue(p.begin(LONG).isPresent());
    }
}
