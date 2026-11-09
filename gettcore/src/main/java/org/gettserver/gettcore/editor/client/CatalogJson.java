package org.gettserver.gettcore.editor.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.gettserver.gettcore.pet.PetSpecies;

/** Pure assembly of the editor catalog JSON. No Minecraft classes, so it is unit-testable. */
public final class CatalogJson {
    public record Entry(String id, String name, String mod) {}

    /**
     * A living entity type with its default attribute base values (mobs tab, hunting entity picker). A null value =
     * the type has no such attribute (e.g. a creeper has no attack damage). {@code category} is its MobCategory name.
     */
    public record MobStat(String id, String name, String mod, String category, Double health, Double attack,
                          Double speed, Double armor) {}

    private CatalogJson() {}

    /**
     * {@code {"items":[{"id","name","mod"}],"entities":[...],"itemTags":[...],"entityTags":[...],
     * "bossTypes":[...],"petKinds":[...],"mobEffects":[{"id","name","mod"}],
     * "mobs":[{"id","name","mod","category","health","attack","speed","armor"}]}}. {@code bossTypes} are the boss entity
     * ids (names come from {@code entities}); {@code petKinds} are the pets.json species kinds; {@code mobEffects} are
     * the potion effects (reinforce milestones); {@code mobs} the living entity types with default stats. Entries are sorted by id and tags/boss types alphabetically so the
     * output is stable.
     */
    public static String build(List<Entry> items, List<Entry> entities, List<String> itemTags, List<String> entityTags,
                               List<String> bossTypes, List<Entry> mobEffects, List<MobStat> mobs) {
        JsonObject root = new JsonObject();
        root.add("items", entries(items));
        root.add("entities", entries(entities));
        root.add("itemTags", strings(itemTags));
        root.add("entityTags", strings(entityTags));
        root.add("bossTypes", strings(bossTypes));
        JsonArray kinds = new JsonArray();
        for (PetSpecies.Kind k : PetSpecies.Kind.values()) kinds.add(k.name().toLowerCase(Locale.ROOT));
        root.add("petKinds", kinds);
        root.add("mobEffects", entries(mobEffects));
        JsonArray m = new JsonArray();
        mobs.stream().sorted(Comparator.comparing(MobStat::id)).forEach(e -> {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.id());
            o.addProperty("name", e.name());
            o.addProperty("mod", e.mod());
            o.addProperty("category", e.category());
            // A missing attribute is left out (not null).
            if (e.health() != null) o.addProperty("health", e.health());
            if (e.attack() != null) o.addProperty("attack", e.attack());
            if (e.speed() != null) o.addProperty("speed", e.speed());
            if (e.armor() != null) o.addProperty("armor", e.armor());
            m.add(o);
        });
        root.add("mobs", m);
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
