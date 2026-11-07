package org.gettserver.gettcore.editor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;

/**
 * Pure helpers for the save RESULT JSON {@code {"saved":bool,"errors":[..],"warnings":[..],"snapshot":<obj|null>}}.
 * The server embeds the new snapshot as a JSON object (see EditorServerService.sendResult).
 */
public final class EditorResultJson {
    private EditorResultJson() {}

    /** The new snapshot as bundle JSON text, if the result carries one. Malformed input gives empty. */
    public static Optional<String> snapshotOf(String resultJson) {
        try {
            JsonElement root = JsonParser.parseString(resultJson);
            if (!root.isJsonObject()) return Optional.empty();
            JsonElement snap = root.getAsJsonObject().get("snapshot");
            if (snap == null || snap.isJsonNull()) return Optional.empty();
            if (snap.isJsonObject()) return Optional.of(snap.toString());
            if (snap.isJsonPrimitive() && snap.getAsJsonPrimitive().isString()) return Optional.of(snap.getAsString());
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** A client-side failure in the same shape the server uses, so the browser handles it the same way. */
    public static String failure(String error) {
        JsonObject o = new JsonObject();
        o.addProperty("saved", false);
        JsonArray errors = new JsonArray();
        errors.add(error);
        o.add("errors", errors);
        o.add("warnings", new JsonArray());
        o.add("snapshot", JsonNull.INSTANCE);
        return o.toString();
    }
}
