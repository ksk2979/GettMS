package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Natural spawns, two parts. Both are biome modifiers, which NeoForge applies once at server start, so every change
 * here needs a server restart.
 * <ul>
 * <li>Mod spawn rules ({@code data/<ns>/neoforge/biome_modifier/*.json} of type {@code neoforge:add_spawns}): a copy
 * with other weight / group sizes, or with type {@code neoforge:none} to turn the rule off. Id {@code wom:spawn_hollow}.
 * <li>Per-mob weight multiplier over every biome (vanilla and mod spawns alike): our own file
 * {@code gettcore:neoforge/biome_modifier/spawn_tweak/<ns>/<path>.json} of type {@code gettcore:scale_spawns}
 * (see {@link ScaleSpawnsModifier}); multiplier 0 removes the mob from natural spawning.
 * </ul>
 */
public final class SpawnBalance {
    public static final String FOLDER = "neoforge/biome_modifier";
    public static final String ADD_SPAWNS = "neoforge:add_spawns";
    public static final String NONE = "neoforge:none";
    /** Type our edited rule copies are written with: {@link SafeAddSpawnsModifier} (never blocks world loading). */
    public static final String SAFE_ADD_SPAWNS = "gettcore:add_spawns";
    public static final String TWEAK_NS = "gettcore";
    public static final String TWEAK_DIR = FOLDER + "/spawn_tweak";
    public static final String TWEAK_TYPE = "gettcore:scale_spawns";
    public static final int WEIGHT_MAX = 1000;
    public static final int COUNT_MAX = 64;
    public static final double MULTIPLIER_MAX = 100;

    public record Outcome(int written, int deleted, List<String> warnings) {
        public boolean changed() {
            return written + deleted > 0;
        }
    }

    /** One spawn entry of one biome, for the per-mob table. */
    public record Spawn(String entity, String category, int weight, int minCount, int maxCount) {}

    private SpawnBalance() {}

    /* ----- mod spawn rules ----- */

    /** {@code wom:neoforge/biome_modifier/spawn_hollow.json} -> {@code wom:spawn_hollow}; null outside the folder. */
    public static String ruleIdOf(ResourceLocation file) {
        String p = file.getPath();
        if (!p.startsWith(FOLDER + "/") || !p.endsWith(".json")) return null;
        return file.getNamespace() + ":" + p.substring(FOLDER.length() + 1, p.length() - 5);
    }

    public static ResourceLocation ruleFileOf(String id) {
        ResourceLocation r = ResourceLocation.tryParse(id);
        return r == null ? null : ResourceLocation.tryBuild(r.getNamespace(), FOLDER + "/" + r.getPath() + ".json");
    }

    public static boolean isSpawnRule(JsonObject o) {
        JsonElement t = o.get("type");
        return t != null && t.isJsonPrimitive() && ADD_SPAWNS.equals(t.getAsString()) && !spawners(o).isEmpty();
    }

    public static JsonArray ruleCatalog(Map<ResourceLocation, JsonObject> originals) {
        JsonArray out = new JsonArray();
        for (Map.Entry<ResourceLocation, JsonObject> e : rules(originals).entrySet()) {
            JsonObject orig = e.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("id", ruleIdOf(e.getKey()));
            row.addProperty("mod", e.getKey().getNamespace());
            row.addProperty("biomes", biomesText(orig.get("biomes")));
            JsonArray sp = new JsonArray();
            for (JsonObject s : spawners(orig)) {
                JsonObject r = new JsonObject();
                JsonElement type = s.get("type");
                r.addProperty("type", type != null && type.isJsonPrimitive() ? type.getAsString() : "");
                for (String k : new String[] {"weight", "minCount", "maxCount"}) {
                    Double v = GunBalance.num(s, k);
                    if (v != null) r.addProperty(k, v.intValue());
                }
                sp.add(r);
            }
            row.add("spawners", sp);
            out.add(row);
        }
        return out;
    }

    public static JsonObject ruleState(Map<ResourceLocation, JsonObject> originals, Path pack) {
        JsonObject out = new JsonObject();
        for (Map.Entry<ResourceLocation, JsonObject> e : rules(originals).entrySet()) {
            PackFiles.read(pack, e.getKey()).ifPresent(copy -> {
                JsonObject d = ruleDiff(e.getValue(), copy);
                if (d != null) out.add(ruleIdOf(e.getKey()), d);
            });
        }
        return out;
    }

    /**
     * {@link #validateRules(JsonObject)} plus the merged result: the edits are applied to the server's original and
     * every resulting spawner must still have minCount ≤ maxCount and counts / weight in range (vanilla's SpawnerData
     * codec rejects min > max, which would stop the world from loading).
     */
    public static List<String> validateRules(Map<ResourceLocation, JsonObject> originals, JsonObject desired) {
        List<String> errs = validateRules(desired);
        if (!errs.isEmpty()) return errs;
        Map<ResourceLocation, JsonObject> rules = rules(originals);
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            ResourceLocation f = ruleFileOf(e.getKey());
            JsonObject orig = f == null ? null : rules.get(f);
            if (orig == null) continue;   // unknown rule: skipped with a warning on save
            JsonObject copy = applyRule(orig, e.getValue().getAsJsonObject());
            if (copy == null || NONE.equals(copy.get("type").getAsString())) continue;
            int i = 0;
            for (JsonObject s : spawners(copy)) {
                i++;
                Double w = GunBalance.num(s, "weight");
                Double min = GunBalance.num(s, "minCount");
                Double max = GunBalance.num(s, "maxCount");
                if (w != null && (w < 1 || w > WEIGHT_MAX)) {
                    errs.add("등장 규칙 " + e.getKey() + " › " + i + "번 가중치: 1~" + WEIGHT_MAX + " 사이여야 합니다");
                }
                if (min != null && max != null && min > max) {
                    errs.add("등장 규칙 " + e.getKey() + " › " + i + "번: 최소 수가 최대 수보다 큽니다 (최소 "
                            + min.intValue() + ", 최대 " + max.intValue() + ")");
                } else if ((min != null && (min < 1 || min > COUNT_MAX)) || (max != null && (max < 1 || max > COUNT_MAX))) {
                    errs.add("등장 규칙 " + e.getKey() + " › " + i + "번: 무리 수는 1~" + COUNT_MAX + " 사이여야 합니다");
                }
            }
        }
        return errs;
    }

    public static List<String> validateRules(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            String id = e.getKey();
            if (!e.getValue().isJsonObject()) {
                errs.add("등장 규칙 " + id + ": 객체여야 합니다");
                continue;
            }
            JsonObject o = e.getValue().getAsJsonObject();
            JsonElement dis = o.get("disabled");
            if (dis != null && !(dis.isJsonPrimitive() && dis.getAsJsonPrimitive().isBoolean())) {
                errs.add("등장 규칙 " + id + " › 끄기: 참/거짓이어야 합니다");
            }
            JsonElement sp = o.get("spawners");
            if (sp == null || sp.isJsonNull()) continue;
            if (!sp.isJsonArray()) {
                errs.add("등장 규칙 " + id + " › spawners: 목록이어야 합니다");
                continue;
            }
            int i = 0;
            for (JsonElement el : sp.getAsJsonArray()) {
                i++;
                if (el.isJsonNull()) continue;
                if (!el.isJsonObject()) {
                    errs.add("등장 규칙 " + id + " › " + i + "번: 객체여야 합니다");
                    continue;
                }
                JsonObject s = el.getAsJsonObject();
                checkInt(errs, id, i, "가중치", s.get("weight"), 1, WEIGHT_MAX);
                checkInt(errs, id, i, "최소 수", s.get("minCount"), 1, COUNT_MAX);
                checkInt(errs, id, i, "최대 수", s.get("maxCount"), 1, COUNT_MAX);
                Double min = GunBalance.num(s, "minCount");
                Double max = GunBalance.num(s, "maxCount");
                if (min != null && max != null && min > max) {
                    errs.add("등장 규칙 " + id + " › " + i + "번: 최소 수가 최대 수보다 큽니다");
                }
            }
        }
        return errs;
    }

    public static Outcome reconcileRules(Map<ResourceLocation, JsonObject> originals, Path pack, JsonObject desired)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<ResourceLocation, JsonObject> rules = rules(originals);
        for (String id : desired.keySet()) {
            ResourceLocation f = ruleFileOf(id);
            if (f == null || !rules.containsKey(f)) warnings.add("등장 규칙 " + id + ": 서버에 없는 규칙이라 건너뜀");
        }
        int written = 0;
        int deleted = 0;
        for (Map.Entry<ResourceLocation, JsonObject> e : rules.entrySet()) {
            JsonElement want = desired.get(ruleIdOf(e.getKey()));
            JsonObject copy = want != null && want.isJsonObject() ? applyRule(e.getValue(), want.getAsJsonObject()) : null;
            if (copy == null) {
                if (PackFiles.delete(pack, e.getKey())) deleted++;
            } else if (PackFiles.write(pack, e.getKey(), copy)) {
                written++;
            }
        }
        return new Outcome(written, deleted, warnings);
    }

    /** Original + edits, or null when the result equals the original. */
    static JsonObject applyRule(JsonObject orig, JsonObject edit) {
        JsonObject copy = orig.deepCopy();
        JsonElement dis = edit.get("disabled");
        if (dis != null && dis.isJsonPrimitive() && dis.getAsJsonPrimitive().isBoolean() && dis.getAsBoolean()) {
            copy.addProperty("type", NONE);
            return copy;
        }
        JsonElement sp = edit.get("spawners");
        if (sp != null && sp.isJsonArray()) {
            List<JsonObject> targets = spawners(copy);
            JsonArray edits = sp.getAsJsonArray();
            for (int i = 0; i < Math.min(targets.size(), edits.size()); i++) {
                if (!edits.get(i).isJsonObject()) continue;
                JsonObject s = edits.get(i).getAsJsonObject();
                for (String k : new String[] {"weight", "minCount", "maxCount"}) {
                    Double v = GunBalance.num(s, k);
                    if (v != null) targets.get(i).addProperty(k, (int) Math.rint(v));
                }
            }
        }
        if (ruleDiff(orig, copy) == null) return null;
        copy.addProperty("type", SAFE_ADD_SPAWNS);
        return copy;
    }

    /** {@code {"disabled":true}}, {@code {"spawners":[...]}} (full list from the copy), or null when equal. */
    static JsonObject ruleDiff(JsonObject orig, JsonObject copy) {
        JsonElement t = copy.get("type");
        if (t != null && t.isJsonPrimitive() && NONE.equals(t.getAsString())) {
            JsonObject o = new JsonObject();
            o.addProperty("disabled", true);
            return o;
        }
        List<JsonObject> a = spawners(orig);
        List<JsonObject> b = spawners(copy);
        boolean differs = a.size() != b.size();
        JsonArray list = new JsonArray();
        for (int i = 0; i < b.size(); i++) {
            JsonObject r = new JsonObject();
            for (String k : new String[] {"weight", "minCount", "maxCount"}) {
                Double v = GunBalance.num(b.get(i), k);
                if (v != null) r.addProperty(k, v.intValue());
                Double ov = i < a.size() ? GunBalance.num(a.get(i), k) : null;
                if (v != null && (ov == null || ov.intValue() != v.intValue())) differs = true;
            }
            list.add(r);
        }
        if (!differs) return null;
        JsonObject o = new JsonObject();
        o.add("spawners", list);
        return o;
    }

    /** Whether a file in the biome modifier folder is one of our per-mob tweak files (not a rule copy). */
    static boolean isTweakFile(ResourceLocation f) {
        return f.getNamespace().equals(TWEAK_NS) && f.getPath().startsWith(TWEAK_DIR + "/");
    }

    /**
     * Rewrites rule copies still in the old {@code neoforge:add_spawns} form (written before
     * {@link #SAFE_ADD_SPAWNS}) to the tolerant type, same content otherwise — same spawns, but a later removal of
     * the mod can no longer stop the world from loading. Returns how many files changed.
     */
    public static int migrateLegacyRuleCopies(Path pack) throws IOException {
        int n = 0;
        for (ResourceLocation f : PackFiles.listFolder(pack, FOLDER)) {
            if (isTweakFile(f)) continue;
            JsonObject o = PackFiles.read(pack, f).orElse(null);
            if (o == null || !isSpawnRule(o)) continue;
            o.addProperty("type", SAFE_ADD_SPAWNS);
            if (PackFiles.write(pack, f, o)) n++;
        }
        return n;
    }

    /* ----- per-mob multiplier ----- */

    /**
     * Whether an entity id can name a tweak file: no empty, "." or ".." path segment (the id becomes folders under
     * our spawn_tweak dir, so "a:../../x" would write outside it) and no "." / ".." namespace.
     */
    public static boolean safeEntityId(ResourceLocation entity) {
        if (entity == null) return false;
        String ns = entity.getNamespace();
        if (ns.isEmpty() || ns.equals(".") || ns.equals("..")) return false;
        for (String seg : entity.getPath().split("/", -1)) {
            if (seg.isEmpty() || seg.equals(".") || seg.equals("..")) return false;
        }
        return true;
    }

    /** Our tweak file for a mob, or null when the id is not {@link #safeEntityId safe}. */
    public static ResourceLocation tweakFileOf(ResourceLocation entity) {
        if (!safeEntityId(entity)) return null;
        return ResourceLocation.fromNamespaceAndPath(TWEAK_NS,
                TWEAK_DIR + "/" + entity.getNamespace() + "/" + entity.getPath() + ".json");
    }

    /** entity id -> multiplier, from our files. */
    public static JsonObject mobState(Path pack) {
        JsonObject out = new JsonObject();
        for (ResourceLocation f : PackFiles.listUnder(pack, TWEAK_NS, TWEAK_DIR)) {
            PackFiles.read(pack, f).ifPresent(o -> {
                JsonElement e = o.get("entity");
                Double m = GunBalance.num(o, "multiplier");
                if (e != null && e.isJsonPrimitive() && m != null) out.addProperty(e.getAsString(), m);
            });
        }
        return out;
    }

    public static List<String> validateMobs(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            if (!safeEntityId(ResourceLocation.tryParse(e.getKey()))) {
                errs.add("몹 등장 배율 " + e.getKey() + ": 올바른 몹 id 가 아닙니다");
                continue;
            }
            Double m = GunBalance.number(e.getValue());
            if (m == null || m < 0 || m > MULTIPLIER_MAX) {
                errs.add("몹 등장 배율 " + e.getKey() + ": 0~" + (int) MULTIPLIER_MAX + " 사이 숫자여야 합니다 (0 = 끄기)");
            }
        }
        return errs;
    }

    /** Our tweak folder is ours alone: files for mobs not in {@code desired} (or at 1) are removed. */
    public static Outcome reconcileMobs(Path pack, JsonObject desired) throws IOException {
        int written = 0;
        int deleted = 0;
        List<ResourceLocation> keep = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            ResourceLocation entity = ResourceLocation.tryParse(e.getKey());
            Double m = GunBalance.number(e.getValue());
            if (!safeEntityId(entity) || m == null || Math.abs(m - 1.0) < 1e-9) continue;
            ResourceLocation f = tweakFileOf(entity);
            keep.add(f);
            JsonObject o = new JsonObject();
            o.addProperty("type", TWEAK_TYPE);
            o.addProperty("entity", entity.toString());
            o.addProperty("multiplier", m);
            if (PackFiles.write(pack, f, o)) written++;
        }
        for (ResourceLocation f : PackFiles.listUnder(pack, TWEAK_NS, TWEAK_DIR)) {
            if (!keep.contains(f) && PackFiles.delete(pack, f)) deleted++;
        }
        return new Outcome(written, deleted, List.of());
    }

    /** New weight for a multiplier: 0 = removed, otherwise rounded and at least 1. */
    public static int scaledWeight(int weight, double multiplier) {
        if (multiplier <= 0) return 0;
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, Math.round(weight * multiplier)));
    }

    /**
     * Per-mob table rows from every biome's current spawn list: biome count and weight / group size ranges.
     * {@code extra} are mobs with one of our files but no current spawn (e.g. turned off), shown with 0 biomes.
     */
    public static JsonArray mobRows(List<Spawn> spawns, List<String> extra) {
        Map<String, JsonObject> rows = new TreeMap<>();
        for (Spawn s : spawns) {
            JsonObject r = rows.get(s.entity());
            if (r == null) {
                r = new JsonObject();
                r.addProperty("entity", s.entity());
                r.addProperty("category", s.category());
                r.addProperty("biomes", 1);
                r.addProperty("minWeight", s.weight());
                r.addProperty("maxWeight", s.weight());
                r.addProperty("minCount", s.minCount());
                r.addProperty("maxCount", s.maxCount());
                rows.put(s.entity(), r);
                continue;
            }
            r.addProperty("biomes", r.get("biomes").getAsInt() + 1);
            r.addProperty("minWeight", Math.min(r.get("minWeight").getAsInt(), s.weight()));
            r.addProperty("maxWeight", Math.max(r.get("maxWeight").getAsInt(), s.weight()));
            r.addProperty("minCount", Math.min(r.get("minCount").getAsInt(), s.minCount()));
            r.addProperty("maxCount", Math.max(r.get("maxCount").getAsInt(), s.maxCount()));
        }
        for (String e : extra) {
            if (rows.containsKey(e)) continue;
            JsonObject r = new JsonObject();
            r.addProperty("entity", e);
            r.addProperty("category", "");
            r.addProperty("biomes", 0);
            rows.put(e, r);
        }
        JsonArray out = new JsonArray();
        rows.values().forEach(out::add);
        return out;
    }

    /* ----- helpers ----- */

    private static Map<ResourceLocation, JsonObject> rules(Map<ResourceLocation, JsonObject> originals) {
        Map<ResourceLocation, JsonObject> out = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        originals.forEach((k, v) -> {
            if (ruleIdOf(k) != null && isSpawnRule(v)) out.put(k, v);
        });
        return out;
    }

    /** The spawner objects of an add_spawns file (a single object or a list), live views into {@code o}. */
    static List<JsonObject> spawners(JsonObject o) {
        List<JsonObject> out = new ArrayList<>();
        JsonElement sp = o.get("spawners");
        if (sp == null) return out;
        if (sp.isJsonObject()) out.add(sp.getAsJsonObject());
        else if (sp.isJsonArray()) {
            for (JsonElement e : sp.getAsJsonArray()) if (e.isJsonObject()) out.add(e.getAsJsonObject());
        }
        return out;
    }

    private static String biomesText(JsonElement b) {
        if (b == null || b.isJsonNull()) return "";
        if (b.isJsonPrimitive()) return b.getAsString();
        if (b.isJsonArray()) {
            List<String> parts = new ArrayList<>();
            for (JsonElement e : b.getAsJsonArray()) parts.add(e.isJsonPrimitive() ? e.getAsString() : e.toString());
            return String.join(", ", parts);
        }
        JsonElement t = b.getAsJsonObject().get("type");
        return t != null && t.isJsonPrimitive() ? t.getAsString() : b.toString();
    }

    private static void checkInt(List<String> errs, String id, int i, String label, JsonElement v, int min, int max) {
        if (v == null || v.isJsonNull()) return;
        Double n = GunBalance.number(v);
        if (n == null || n != Math.rint(n) || n < min || n > max) {
            errs.add("등장 규칙 " + id + " › " + i + "번 " + label + ": " + min + "~" + max + " 사이 정수여야 합니다");
        }
    }
}
