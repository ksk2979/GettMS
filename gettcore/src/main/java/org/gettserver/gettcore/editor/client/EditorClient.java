package org.gettserver.gettcore.editor.client;

import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.network.EditorDataPayload;

/** Client side of the web editor. Stub for now; the real implementation arrives with the HTTP bridge. */
public final class EditorClient {
    private EditorClient() {}

    public static void onData(EditorDataPayload p) {
        GettCore.LOGGER.info("editor data {} session={} chunk {}/{} ({} bytes)",
                p.kind(), p.sessionId(), p.index() + 1, p.total(), p.data().length);
    }
}
