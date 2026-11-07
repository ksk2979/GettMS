package org.gettserver.gettcore.editor;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.data.DialogDefinition;
import org.gettserver.gettcore.data.NpcMapping;
import org.gettserver.gettcore.data.ShopDefinition;
import org.gettserver.gettcore.quest.QuestLine;

/** Bundle JSON as in spec 3.4: same per-file formats as the on-disk files, via the existing codecs. */
public final class EditorJson {
    private static final Gson GSON = new Gson();

    private EditorJson() {}

    public static String encode(EditorBundle bundle, EditorRefs refs) {
        JsonObject root = new JsonObject();
        root.add("shops", encodeMap(bundle.shops(), ShopDefinition.CODEC));
        root.add("dialogs", encodeMap(bundle.dialogs(), DialogDefinition.CODEC));
        root.add("npcs", NpcMapping.CODEC.encodeStart(JsonOps.INSTANCE, bundle.npcs()).getOrThrow());
        root.add("quests", encodeMap(bundle.quests(), QuestLine.CODEC));
        if (refs != null) {
            JsonObject r = new JsonObject();
            JsonArray adv = new JsonArray();
            for (EditorRefs.AdvRef a : refs.advancements()) {
                JsonObject o = new JsonObject();
                o.addProperty("id", a.id());
                o.addProperty("title", a.title());
                adv.add(o);
            }
            r.add("advancements", adv);
            r.add("missingItems", strings(refs.missingItems()));
            r.add("missingEntities", strings(refs.missingEntities()));
            root.add("refs", r);
        }
        return GSON.toJson(root);
    }

    public static EditorBundle decode(String json) {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("bundle: invalid JSON: " + e.getMessage());
        }
        if (!parsed.isJsonObject()) throw new IllegalArgumentException("bundle: expected a JSON object");
        JsonObject root = parsed.getAsJsonObject();
        requireSection(root, "npcs");
        NpcMapping npcs = NpcMapping.CODEC.parse(JsonOps.INSTANCE, root.get("npcs")).getOrThrow(
                m -> new IllegalArgumentException("npcs: " + m));
        return new EditorBundle(decodeMap(root, "shops", ShopDefinition.CODEC),
                decodeMap(root, "dialogs", DialogDefinition.CODEC), npcs,
                decodeMap(root, "quests", QuestLine.CODEC));
    }

    /** A save replaces everything, so a missing section must be an error rather than "empty". */
    private static void requireSection(JsonObject root, String section) {
        if (!root.has(section) || root.get(section).isJsonNull()) {
            throw new IllegalArgumentException(section + ": missing section");
        }
    }

    private static JsonArray strings(List<String> l) {
        JsonArray a = new JsonArray();
        l.forEach(a::add);
        return a;
    }

    private static <T> JsonObject encodeMap(Map<String, T> map, Codec<T> codec) {
        JsonObject o = new JsonObject();
        map.forEach((k, v) -> o.add(k, codec.encodeStart(JsonOps.INSTANCE, v).getOrThrow()));
        return o;
    }

    private static <T> Map<String, T> decodeMap(JsonObject root, String section, Codec<T> codec) {
        Map<String, T> out = new LinkedHashMap<>();
        requireSection(root, section);
        if (!root.get(section).isJsonObject()) throw new IllegalArgumentException(section + ": expected an object");
        for (Map.Entry<String, JsonElement> e : root.getAsJsonObject(section).entrySet()) {
            String path = section + "." + e.getKey();
            out.put(e.getKey(), codec.parse(JsonOps.INSTANCE, e.getValue()).getOrThrow(
                    m -> new IllegalArgumentException(path + ": " + m)));
        }
        return out;
    }
}
