package org.gettserver.gettcore.editor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;

/** Pure assembly of the editor catalog JSON. No Minecraft classes, so it is unit-testable. */
public final class CatalogJson {
    public record Entry(String id, String name, String mod) {}

    private CatalogJson() {}

    /**
     * {@code {"items":[{"id","name","mod"}],"entities":[...],"itemTags":[...],"entityTags":[...]}}.
     * Entries are sorted by id and tags alphabetically so the output is stable.
     */
    public static String build(List<Entry> items, List<Entry> entities, List<String> itemTags, List<String> entityTags) {
        JsonObject root = new JsonObject();
        root.add("items", entries(items));
        root.add("entities", entries(entities));
        root.add("itemTags", strings(itemTags));
        root.add("entityTags", strings(entityTags));
        return root.toString();
    }

    private static JsonArray entries(List<Entry> list) {
        JsonArray a = new JsonArray();
        list.stream().sorted(Comparator.comparing(Entry::id)).forEach(e -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("name", e.name());
            o.addProperty("mod", e.mod());
            a.add(o);
        });
        return a;
    }

    private static JsonArray strings(List<String> list) {
        JsonArray a = new JsonArray();
        list.stream().sorted().forEach(a::add);
        return a;
    }
}
