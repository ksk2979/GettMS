package org.gettserver.gettcore.editor.client;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * At most one save in flight. The server's RESULT carries no client save id, so a second save while one is
 * pending could get the wrong answer; instead it is refused. A pending save expires after a timeout so a lost
 * RESULT cannot block saving forever. Pure (no Minecraft classes) and thread-safe.
 */
public final class PendingSave {
    private CompletableFuture<String> pending;

    /** A new pending future, or empty if another save is still waiting for its result. */
    public synchronized Optional<CompletableFuture<String>> begin(Duration timeout) {
        if (pending != null && !pending.isDone()) return Optional.empty();
        CompletableFuture<String> f = new CompletableFuture<>();
        f.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        pending = f;
        return Optional.of(f);
    }

    /** Completes the waiting save, if any. Returns true if one was waiting. */
    public synchronized boolean complete(String resultJson) {
        CompletableFuture<String> f = pending;
        pending = null;
        return f != null && f.complete(resultJson);
    }

    /** Answers the given save locally (e.g. it could not be sent) and frees the slot if it is still current. */
    public synchronized void finish(CompletableFuture<String> f, String resultJson) {
        if (pending == f) pending = null;
        f.complete(resultJson);
    }

    /** Fails whatever save is waiting (editor closed). */
    public synchronized void failAll(Throwable cause) {
        CompletableFuture<String> f = pending;
        pending = null;
        if (f != null) f.completeExceptionally(cause);
    }
}
