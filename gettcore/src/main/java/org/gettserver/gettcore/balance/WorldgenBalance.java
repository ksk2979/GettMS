package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Turning off what a mod adds to world generation, two kinds. Both are read once when the world loads, so every
 * change here needs a server restart, and only chunks generated afterwards are affected.
 * <ul>
 * <li>Structures ({@code data/<ns>/worldgen/structure_set/*.json}): a copy with an empty {@code structures} list.
 * A set without structures has no biome it can generate in, so the chunk generator never tries it; the placement
 * (and so any other set's exclusion zone that points at it) stays valid. Id {@code structure/<ns>:<path>}.
 * <li>Ores and other features a mod adds to biomes ({@code data/<ns>/neoforge/biome_modifier/*.json} of type
 * {@code neoforge:add_features}): a copy of type {@code neoforge:none}. Id {@code feature/<ns>:<path>}.
 * </ul>
 * Vanilla ({@code minecraft}) structure sets are not listed. Natural mob spawns are the spawn tab's job
 * ({@link SpawnBalance}); it only looks at {@code neoforge:add_spawns} files, so the two never touch the same file.
 *
 * <p>Editor state: {@code datapack.worldgen = {id: true}} (turned off ones). Catalog: rows {id, mod, kind, detail}.
 */
public final class WorldgenBalance {
    public static final String STRUCTURE_FOLDER = "worldgen/structure_set";
    public static final String MODIFIER_FOLDER = SpawnBalance.FOLDER;
    public static final String ADD_FEATURES = "neoforge:add_features";
    public static final String STRUCTURE = "structure";
    public static final String FEATURE = "feature";

    public record Row(ResourceLocation file, String id, String kind, String detail) {}

    public record Outcome(int written, int deleted, List<String> warnings) {
        public boolean changed() {
            return written + deleted > 0;
        }
    }

    private WorldgenBalance() {}

    /** {@code structure/irons_spellbooks:citadel} or {@code feature/irons_spellbooks:add_mithril_ore}; null otherwise. */
    public static String idOf(ResourceLocation file) {
        String p = file.getPath();
        if (!p.endsWith(".json")) return null;
        for (String[] k : new String[][] {{STRUCTURE, STRUCTURE_FOLDER}, {FEATURE, MODIFIER_FOLDER}}) {
            String prefix = k[1] + "/";
            if (p.startsWith(prefix) && p.length() > prefix.length() + 5) {
                return k[0] + "/" + file.getNamespace() + ":" + p.substring(prefix.length(), p.length() - 5);
            }
        }
        return null;
    }

    public static ResourceLocation fileOf(String id) {
        int slash = id == null ? -1 : id.indexOf('/');
        if (slash < 0) return null;
        String kind = id.substring(0, slash);
        String folder = kind.equals(STRUCTURE) ? STRUCTURE_FOLDER : kind.equals(FEATURE) ? MODIFIER_FOLDER : null;
        ResourceLocation r = folder == null ? null : ResourceLocation.tryParse(id.substring(slash + 1));
        return r == null ? null : ResourceLocation.tryBuild(r.getNamespace(), folder + "/" + r.getPath() + ".json");
    }

    private static String typeOf(JsonObject o) {
        return o.get("type") instanceof JsonPrimitive p && p.isString() ? p.getAsString() : "";
    }

    private static boolean isStructureSet(JsonObject o) {
        return o.get("structures") instanceof JsonArray && o.has("placement");
    }

    /** The structures a set picks from ("irons_spellbooks:citadel, ..."), or the features a modifier adds. */
    static String detail(String kind, JsonObject orig) {
        List<String> names = new ArrayList<>();
        if (kind.equals(STRUCTURE)) {
            for (JsonElement e : orig.getAsJsonArray("structures")) {
                if (e instanceof JsonObject s && s.get("structure") instanceof JsonPrimitive p) names.add(p.getAsString());
            }
        } else {
            JsonElement f = orig.get("features");
            if (f instanceof JsonPrimitive p) names.add(p.getAsString());
            else if (f instanceof JsonArray a) a.forEach(x -> { if (x instanceof JsonPrimitive p) names.add(p.getAsString()); });
        }
        return String.join(", ", names);
    }

    /** A structure set copy without structures, or a {@code neoforge:none} modifier. */
    public static JsonObject disabledCopy(String kind, JsonObject orig) {
        JsonObject copy = new JsonObject();
        if (kind.equals(STRUCTURE)) {
            copy.add("structures", new JsonArray());
            copy.add("placement", orig.get("placement").deepCopy());
        } else {
            copy.addProperty("type", SpawnBalance.NONE);
        }
        return copy;
    }

    /** Whether a file in our pack is one of our turned-off copies. */
    public static boolean isDisabled(String kind, JsonObject copy) {
        if (kind.equals(STRUCTURE)) return copy.get("structures") instanceof JsonArray a && a.isEmpty();
        return SpawnBalance.NONE.equals(typeOf(copy));
    }

    /**
     * Listed entries, sorted by id: mod structure sets that pick at least one structure and mod biome modifiers that
     * add features.
     *
     * @param structures originals under {@link #STRUCTURE_FOLDER}
     * @param modifiers  originals under {@link #MODIFIER_FOLDER}
     */
    public static Map<String, Row> rows(Map<ResourceLocation, JsonObject> structures,
                                        Map<ResourceLocation, JsonObject> modifiers) {
        Map<String, Row> out = new TreeMap<>(Comparator.naturalOrder());
        for (Map.Entry<ResourceLocation, JsonObject> e : structures.entrySet()) {
            String id = idOf(e.getKey());
            if (id == null || !id.startsWith(STRUCTURE + "/") || e.getValue() == null) continue;
            if (e.getKey().getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) continue;
            if (!isStructureSet(e.getValue()) || e.getValue().getAsJsonArray("structures").isEmpty()) continue;
            out.put(id, new Row(e.getKey(), id, STRUCTURE, detail(STRUCTURE, e.getValue())));
        }
        for (Map.Entry<ResourceLocation, JsonObject> e : modifiers.entrySet()) {
            String id = idOf(e.getKey());
            if (id == null || !id.startsWith(FEATURE + "/") || e.getValue() == null) continue;
            if (!ADD_FEATURES.equals(typeOf(e.getValue()))) continue;
            out.put(id, new Row(e.getKey(), id, FEATURE, detail(FEATURE, e.getValue())));
        }
        return out;
    }

    public static JsonArray catalog(Map<String, Row> rows) {
        JsonArray out = new JsonArray();
        for (Row r : rows.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("mod", r.file().getNamespace());
            o.addProperty("kind", r.kind());
            o.addProperty("detail", r.detail());
            out.add(o);
        }
        return out;
    }

    /** Turned-off entries ({id: true}) among the listed ones. */
    public static JsonObject state(Map<String, Row> rows, Path pack) {
        JsonObject out = new JsonObject();
        for (Row r : rows.values()) {
            if (PackFiles.read(pack, r.file()).filter(c -> isDisabled(r.kind(), c)).isPresent()) {
                out.addProperty(r.id(), true);
            }
        }
        return out;
    }

    /** Errors for the save: id -> true/false. */
    public static List<String> validate(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            if (fileOf(e.getKey()) == null) {
                errs.add("지형 " + e.getKey() + ": 잘못된 id");
            } else if (!(e.getValue() instanceof JsonPrimitive p) || !p.isBoolean()) {
                errs.add("지형 " + e.getKey() + ": true/false 여야 합니다");
            }
        }
        return errs;
    }

    /**
     * Makes our copies match {@code desired} for every listed entry: off -> turned-off copy, otherwise our turned-off
     * copy is deleted (any other file at that path is left alone). Requests for unlisted ids are skipped with a
     * warning. Call {@link #validate} first.
     */
    public static Outcome reconcile(Map<ResourceLocation, JsonObject> structures,
                                    Map<ResourceLocation, JsonObject> modifiers, Path pack, JsonObject desired)
            throws IOException {
        Map<String, Row> rows = rows(structures, modifiers);
        List<String> warnings = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            if (off(e.getValue()) && !rows.containsKey(e.getKey())) {
                warnings.add("지형 " + e.getKey() + ": 서버에 없는 항목이라 건너뜀");
            }
        }
        int written = 0;
        int deleted = 0;
        for (Row r : rows.values()) {
            JsonObject orig = (r.kind().equals(STRUCTURE) ? structures : modifiers).get(r.file());
            if (off(desired.get(r.id()))) {
                if (PackFiles.write(pack, r.file(), disabledCopy(r.kind(), orig))) written++;
            } else if (PackFiles.read(pack, r.file()).filter(c -> isDisabled(r.kind(), c)).isPresent()
                    && PackFiles.delete(pack, r.file())) {
                deleted++;
            }
        }
        return new Outcome(written, deleted, warnings);
    }

    private static boolean off(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
    }
}
