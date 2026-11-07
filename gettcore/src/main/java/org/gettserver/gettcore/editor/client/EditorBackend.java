package org.gettserver.gettcore.editor.client;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Data source behind the local editor HTTP server. Implementations must be thread-safe. */
public interface EditorBackend {
    String snapshotJson();

    String catalogJson();

    Optional<byte[]> iconPng(String namespace, String path);

    CompletableFuture<String> save(String bundleJson);
}
