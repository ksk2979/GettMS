package org.gettserver.gettcore.editor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Optional;
import java.util.function.UnaryOperator;

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

    /**
     * The server sends advancement titles as translation keys (it has no client language); rewrites each
     * {@code refs.advancements[].title} of a snapshot with {@code translate}. Malformed input is returned unchanged.
     */
    public static String localizeSnapshot(String snapshotJson, UnaryOperator<String> translate) {
        try {
            JsonElement root = JsonParser.parseString(snapshotJson);
            if (!root.isJsonObject() || !localizeRefs(root.getAsJsonObject(), translate)) return snapshotJson;
            return root.toString();
        } catch (RuntimeException e) {
            return snapshotJson;
        }
    }

    /** {@link #localizeSnapshot} applied to the snapshot embedded in a save RESULT. */
    public static String localizeResult(String resultJson, UnaryOperator<String> translate) {
        try {
            JsonElement root = JsonParser.parseString(resultJson);
            if (!root.isJsonObject()) return resultJson;
            JsonElement snap = root.getAsJsonObject().get("snapshot");
            if (snap == null || !snap.isJsonObject() || !localizeRefs(snap.getAsJsonObject(), translate)) {
                return resultJson;
            }
            return root.toString();
        } catch (RuntimeException e) {
            return resultJson;
        }
    }

    private static boolean localizeRefs(JsonObject snapshot, UnaryOperator<String> translate) {
        JsonElement refs = snapshot.get("refs");
        if (refs == null || !refs.isJsonObject()) return false;
        JsonElement advs = refs.getAsJsonObject().get("advancements");
        if (advs == null || !advs.isJsonArray()) return false;
        for (JsonElement a : advs.getAsJsonArray()) {
            if (!a.isJsonObject()) continue;
            JsonElement t = a.getAsJsonObject().get("title");
            if (t != null && t.isJsonPrimitive() && t.getAsJsonPrimitive().isString()) {
                a.getAsJsonObject().addProperty("title", translate.apply(t.getAsString()));
            }
        }
        return true;
    }
}
