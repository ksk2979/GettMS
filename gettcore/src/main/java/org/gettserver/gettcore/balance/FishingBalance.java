package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Predicate;
import net.minecraft.resources.ResourceLocation;

/**
 * Fishing odds of the Tide mod (2.1.1 data layout):
 *
 * <ul>
 *   <li>{@code data/<ns>/fishing/fish/**.json} — one fish each, {@code selection_weight} / {@code selection_quality}
 *   <li>{@code data/<ns>/fishing/loot/*.json} — a junk / treasure group: {@code loot_table}, {@code weight}, {@code quality}
 *   <li>{@code data/<ns>/fishing/crates/*.json} — a crate type: {@code weight}
 *   <li>the loot tables those groups point at (vanilla junk / treasure and Tide's own): entry weights, added items
 * </ul>
 *
 * Tide picks between "a fish" (fixed weight 85), each loot group and "a crate", then inside the fish group between
 * the fish whose conditions match. An entry whose weight ends up 0 or less is never picked; luck adds
 * {@code quality × luck} to the weight, so "0 = never" also clears the quality in the copy.
 *
 * <p>Ids are file locations without ".json" ({@code tide:fishing/fish/freshwater/carp}); tables use the loot table
 * id ({@code minecraft:gameplay/fishing/junk}). A copy is the mod's original file with only these values replaced.
 */
public final class FishingBalance {
    public static final String FISH_FOLDER = "fishing/fish";
    public static final String LOOT_FOLDER = "fishing/loot";
    public static final String CRATE_FOLDER = "fishing/crates";
    public static final String TABLE_FOLDER = "loot_table";

    public static final String FISH = "fish";
    public static final String LOOT = "loot";
    public static final String CRATES = "crates";
    public static final String TABLES = "tables";
    public static final String WEIGHTS = "weights";
    public static final String ADDED = "added";

    public static final double WEIGHT_MAX = 10000;
    public static final int COUNT_MAX = 64;
    /** Tide's fixed weight of "a fish" against the loot groups (FishSelector). */
    public static final double FISH_GROUP_WEIGHT = 85;

    /** One kind of weighted data file: where it lives and what its weight / quality fields are called. */
    public record Kind(String key, String folder, String weightField, String qualityField, String label) {}

    public static final Kind FISH_KIND = new Kind(FISH, FISH_FOLDER, "selection_weight", "selection_quality", "물고기");
    public static final Kind LOOT_KIND = new Kind(LOOT, LOOT_FOLDER, "weight", "quality", "쓰레기·보물 묶음");
    public static final Kind CRATE_KIND = new Kind(CRATES, CRATE_FOLDER, "weight", "quality", "상자");
    public static final List<Kind> KINDS = List.of(FISH_KIND, LOOT_KIND, CRATE_KIND);

    /** The mods' original files, per kind, plus the loot tables the loot groups use (table id -> file content). */
    public record Originals(Map<ResourceLocation, JsonObject> fish, Map<ResourceLocation, JsonObject> loot,
                            Map<ResourceLocation, JsonObject> crates, Map<String, JsonObject> tables) {
        public static final Originals EMPTY = new Originals(Map.of(), Map.of(), Map.of(), Map.of());

        Map<ResourceLocation, JsonObject> of(Kind kind) {
            return kind == FISH_KIND ? fish : kind == LOOT_KIND ? loot : crates;
        }

        public boolean isEmpty() {
            return fish.isEmpty() && loot.isEmpty() && crates.isEmpty();
        }
    }

    public record Outcome(int written, int deleted, List<String> warnings) {
        public boolean changed() {
            return written + deleted > 0;
        }
    }

    private FishingBalance() {}

    public static String idOf(ResourceLocation file) {
        return GunBalance.idOf(file);
    }

    public static ResourceLocation fileOf(String id) {
        return GunBalance.fileOf(id);
    }

    /** {@code minecraft:gameplay/fishing/junk} -> {@code minecraft:loot_table/gameplay/fishing/junk.json}. */
    public static ResourceLocation tableFile(String tableId) {
        ResourceLocation id = ResourceLocation.tryParse(tableId);
        return id == null ? null : ResourceLocation.tryBuild(id.getNamespace(), TABLE_FOLDER + "/" + id.getPath() + ".json");
    }

    /** Loot table ids the loot groups point at, in group order. */
    public static List<String> tableIds(Map<ResourceLocation, JsonObject> loot) {
        List<String> out = new ArrayList<>();
        for (JsonObject o : sorted(loot).values()) {
            String t = str(o, "loot_table");
            if (t != null && ResourceLocation.tryParse(t) != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    // ---- catalog (original values) --------------------------------------------------------------------------

    /** @param itemExists fish of mods that are not installed (their item is not registered) are left out */
    public static JsonObject catalog(Originals o, Predicate<ResourceLocation> itemExists) {
        JsonObject out = new JsonObject();
        JsonArray fish = new JsonArray();
        for (Map.Entry<ResourceLocation, JsonObject> e : sorted(o.fish()).entrySet()) {
            JsonObject f = e.getValue();
            String item = str(f, "fish");
            ResourceLocation itemId = item == null ? null : ResourceLocation.tryParse(item);
            if (itemId == null || !itemExists.test(itemId)) continue;
            if (GunBalance.num(f, FISH_KIND.weightField()) == null) continue;   // variants that follow a parent fish
            JsonObject row = weightRow(FISH_KIND, e.getKey(), f);
            row.addProperty("item", item);
            JsonObject profile = GunBalance.obj(f, "journal_profile");
            String rarity = profile == null ? null : str(profile, "rarity");
            row.addProperty("rarity", rarity == null ? "common" : rarity);
            String group = profile == null ? null : str(profile, "group");
            row.addProperty("group", group == null ? "" : group);
            fish.add(row);
        }
        out.add(FISH, fish);
        JsonArray loot = new JsonArray();
        for (Map.Entry<ResourceLocation, JsonObject> e : sorted(o.loot()).entrySet()) {
            String table = str(e.getValue(), "loot_table");
            if (table == null) continue;
            JsonObject row = weightRow(LOOT_KIND, e.getKey(), e.getValue());
            row.addProperty("table", table);
            loot.add(row);
        }
        out.add(LOOT, loot);
        JsonArray crates = new JsonArray();
        for (Map.Entry<ResourceLocation, JsonObject> e : sorted(o.crates()).entrySet()) {
            String table = str(e.getValue(), "loot_table");
            if (table == null) continue;   // crates of mods that are not installed
            JsonObject row = weightRow(CRATE_KIND, e.getKey(), e.getValue());
            row.addProperty("table", table);
            crates.add(row);
        }
        out.add(CRATES, crates);
        JsonArray tables = new JsonArray();
        for (Map.Entry<String, JsonObject> e : new TreeMap<>(o.tables()).entrySet()) {
            JsonObject row = new JsonObject();
            row.addProperty("id", e.getKey());
            JsonArray entries = entriesOf(e.getValue());
            row.addProperty("editable", entries != null);
            JsonArray rows = new JsonArray();
            if (entries != null) {
                for (int i = 0; i < entries.size(); i++) rows.add(entryRow(i, entries.get(i)));
            }
            row.add("entries", rows);
            tables.add(row);
        }
        out.add(TABLES, tables);
        out.addProperty("fishGroupWeight", FISH_GROUP_WEIGHT);
        return out;
    }

    private static JsonObject weightRow(Kind kind, ResourceLocation file, JsonObject o) {
        JsonObject row = new JsonObject();
        row.addProperty("id", idOf(file));
        row.addProperty("mod", file.getNamespace());
        row.addProperty("weight", orZero(GunBalance.num(o, kind.weightField())));
        row.addProperty("quality", orZero(GunBalance.num(o, kind.qualityField())));
        JsonArray where = new JsonArray();
        whereOf(o.get("conditions")).forEach(where::add);
        row.add("where", where);
        return row;
    }

    private static JsonObject entryRow(int index, JsonElement entry) {
        JsonObject row = new JsonObject();
        row.addProperty("key", String.valueOf(index));
        JsonObject e = entry.isJsonObject() ? entry.getAsJsonObject() : new JsonObject();
        String type = str(e, "type");
        String name = str(e, "name");
        row.addProperty("type", type == null ? "" : type);
        row.addProperty("name", name == null ? "" : name);
        row.addProperty("item", "minecraft:item".equals(type) && name != null ? name : "");
        row.addProperty("weight", entryWeight(e));
        JsonArray notes = new JsonArray();
        JsonElement fns = e.get("functions");
        if (fns != null && fns.isJsonArray()) {
            for (JsonElement f : fns.getAsJsonArray()) {
                String fn = f.isJsonObject() ? str(f.getAsJsonObject(), "function") : null;
                String note = fn == null ? null : FUNCTION_NOTES.get(fn);
                if (note != null && !contains(notes, note)) notes.add(note);
            }
        }
        row.add("notes", notes);
        return row;
    }

    private static final Map<String, String> FUNCTION_NOTES = Map.of(
            "minecraft:enchant_with_levels", "무작위 마법부여",
            "minecraft:enchant_randomly", "무작위 마법부여",
            "minecraft:set_damage", "닳은 상태",
            "minecraft:set_count", "여러 개",
            "minecraft:set_potion", "물약",
            "minecraft:set_components", "특수 아이템");

    private static final Map<String, String> DIMENSIONS = Map.of(
            "minecraft:the_nether", "네더", "minecraft:the_end", "엔드");
    private static final Map<String, String> BIOMES = Map.ofEntries(
            Map.entry("cherry_grove", "벚나무 숲"), Map.entry("coastal", "해안"), Map.entry("desert", "사막"),
            Map.entry("dripstone", "점적석 동굴"), Map.entry("frozen", "얼어붙은 곳"), Map.entry("jungle", "정글"),
            Map.entry("mushroom", "버섯 들판"), Map.entry("swamp", "늪"), Map.entry("deep_dark", "깊은 어둠"),
            Map.entry("plains", "평원"), Map.entry("deep_ocean", "깊은 바다"), Map.entry("river", "강"),
            Map.entry("arid", "건조 지대"), Map.entry("normal_ocean", "바다"), Map.entry("warm_ocean", "따뜻한 바다"));

    /** Short Korean notes on where / when an entry can be caught, from its Tide conditions. */
    static List<String> whereOf(JsonElement conditions) {
        List<String> out = new ArrayList<>();
        if (conditions == null || !conditions.isJsonArray()) return out;
        for (JsonElement el : conditions.getAsJsonArray()) {
            if (!el.isJsonObject()) continue;
            JsonObject c = el.getAsJsonObject();
            String type = str(c, "type");
            if (type == null) continue;
            switch (type) {
                case "tide:freshwater" -> out.add("민물");
                case "tide:saltwater" -> out.add("바다");
                case "tide:fluid" -> {
                    String f = str(c, "fluid");
                    if ("lava".equals(f)) out.add("용암");
                    else if ("void".equals(f)) out.add("공허");
                }
                case "tide:dimension" -> {
                    for (String d : strings(c.get("dimensions"))) {
                        if (!d.equals("minecraft:overworld")) out.add(DIMENSIONS.getOrDefault(d, d));
                    }
                }
                case "tide:above" -> out.add("지상");
                case "tide:below" -> out.add("y " + fmt(orZero(GunBalance.num(c, "y"))) + " 아래");
                case "tide:depth_range" -> out.add("y " + fmt(orZero(GunBalance.num(c, "min_y"))) + "~"
                        + fmt(orZero(GunBalance.num(c, "max_y"))));
                case "tide:found_in" -> {
                    List<String> names = new ArrayList<>();
                    for (String b : strings(c.get("biomes"))) names.add(biomeName(b));
                    if (!names.isEmpty()) out.add(String.join("·", names));
                }
                case "tide:found_in_structures" -> out.add("구조물 근처");
                case "tide:block_nearby" -> out.add("특정 블록 근처");
                case "tide:open_water" -> out.add("넓은 물");
                case "tide:seasons" -> out.add("계절 한정");
                case "tide:time_of_day" -> out.add("시간대 한정");
                case "tide:moon_phase" -> out.add("달 모양 한정");
                case "tide:weather" -> out.add("날씨 한정");
                case "tide:luck" -> out.add("행운 " + fmt(orZero(GunBalance.num(c, "min"))) + " 이상");
                default -> out.add("특수 조건");
            }
        }
        return out;
    }

    private static String biomeName(String tag) {
        String s = tag.startsWith("#") ? tag.substring(1) : tag;
        int colon = s.indexOf(':');
        if (colon >= 0) s = s.substring(colon + 1);
        if (s.startsWith("has_")) s = s.substring(4);
        if (s.startsWith("is_")) s = s.substring(3);
        if (s.endsWith("_fish")) s = s.substring(0, s.length() - 5);
        if (s.endsWith("_biome")) s = s.substring(0, s.length() - 6);
        return BIOMES.getOrDefault(s, s);
    }

    // ---- state (what our copies change) ---------------------------------------------------------------------

    public static JsonObject state(Originals o, Path pack) {
        JsonObject out = new JsonObject();
        for (Kind kind : KINDS) {
            JsonObject section = new JsonObject();
            for (Map.Entry<ResourceLocation, JsonObject> e : sorted(o.of(kind)).entrySet()) {
                PackFiles.read(pack, e.getKey()).ifPresent(copy -> {
                    double a = orZero(GunBalance.num(e.getValue(), kind.weightField()));
                    double b = orZero(GunBalance.num(copy, kind.weightField()));
                    if (Math.abs(a - b) > 1e-9) section.addProperty(idOf(e.getKey()), b);
                });
            }
            out.add(kind.key(), section);
        }
        JsonObject tables = new JsonObject();
        for (Map.Entry<String, JsonObject> e : new TreeMap<>(o.tables()).entrySet()) {
            ResourceLocation file = tableFile(e.getKey());
            if (file == null) continue;
            PackFiles.read(pack, file).ifPresent(copy -> {
                JsonObject d = tableDiff(e.getValue(), copy);
                if (d.size() > 0) tables.add(e.getKey(), d);
            });
        }
        out.add(TABLES, tables);
        return out;
    }

    // ---- validate -------------------------------------------------------------------------------------------

    public static List<String> validate(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Kind kind : KINDS) {
            JsonElement s = desired.get(kind.key());
            if (s == null || s.isJsonNull()) continue;
            if (!s.isJsonObject()) {
                errs.add("낚시 › " + kind.label() + ": 객체여야 합니다");
                continue;
            }
            for (Map.Entry<String, JsonElement> e : s.getAsJsonObject().entrySet()) {
                Double n = GunBalance.number(e.getValue());
                if (n == null || n < 0 || n > WEIGHT_MAX) {
                    errs.add("낚시 › " + kind.label() + " " + e.getKey() + ": 가중치는 0~" + (int) WEIGHT_MAX + " 사이 숫자여야 합니다");
                }
            }
        }
        JsonElement t = desired.get(TABLES);
        if (t != null && !t.isJsonNull()) {
            if (!t.isJsonObject()) {
                errs.add("낚시 › 내용물: 객체여야 합니다");
                return errs;
            }
            for (Map.Entry<String, JsonElement> e : t.getAsJsonObject().entrySet()) {
                String where = "낚시 › 내용물 " + e.getKey();
                if (!e.getValue().isJsonObject()) {
                    errs.add(where + ": 객체여야 합니다");
                    continue;
                }
                JsonObject edits = e.getValue().getAsJsonObject();
                JsonElement w = edits.get(WEIGHTS);
                if (w != null && !w.isJsonNull()) {
                    if (!w.isJsonObject()) {
                        errs.add(where + ": weights 는 객체여야 합니다");
                    } else {
                        for (Map.Entry<String, JsonElement> x : w.getAsJsonObject().entrySet()) {
                            if (!isWhole(GunBalance.number(x.getValue()), 0, WEIGHT_MAX)) {
                                errs.add(where + " › " + (parseIndex(x.getKey()) + 1) + "번째: 가중치는 0~" + (int) WEIGHT_MAX + " 사이 정수여야 합니다");
                            }
                        }
                    }
                }
                JsonElement a = edits.get(ADDED);
                if (a != null && !a.isJsonNull()) {
                    if (!a.isJsonArray()) {
                        errs.add(where + ": added 는 목록이어야 합니다");
                        continue;
                    }
                    int i = 0;
                    for (JsonElement x : a.getAsJsonArray()) {
                        i++;
                        String row = where + " › 추가한 아이템 " + i + "번";
                        if (!x.isJsonObject()) {
                            errs.add(row + ": 객체여야 합니다");
                            continue;
                        }
                        JsonObject add = x.getAsJsonObject();
                        String item = str(add, "item");
                        if (item == null || ResourceLocation.tryParse(item) == null) errs.add(row + ": 아이템 id 가 올바르지 않습니다");
                        if (!isWhole(GunBalance.num(add, "weight"), 1, WEIGHT_MAX)) {
                            errs.add(row + ": 가중치는 1~" + (int) WEIGHT_MAX + " 사이 정수여야 합니다");
                        }
                        Double min = GunBalance.num(add, "min");
                        Double max = GunBalance.num(add, "max");
                        if (!isWhole(min, 1, COUNT_MAX) || !isWhole(max, 1, COUNT_MAX) || max < min) {
                            errs.add(row + ": 개수는 1~" + COUNT_MAX + " 사이 정수이고 최소 ≤ 최대여야 합니다");
                        }
                    }
                }
            }
        }
        return errs;
    }

    // ---- apply ----------------------------------------------------------------------------------------------

    /**
     * Makes our copies match {@code desired} for every file in {@code o}: a copy where something differs from the
     * original, no copy otherwise. Call {@link #validate} first.
     *
     * @param itemExists added items that are not registered are skipped with a warning
     */
    public static Outcome reconcile(Originals o, Path pack, JsonObject desired, Predicate<ResourceLocation> itemExists)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        int written = 0;
        int deleted = 0;
        for (Kind kind : KINDS) {
            JsonObject want = GunBalance.obj(desired, kind.key());
            Map<ResourceLocation, JsonObject> originals = sorted(o.of(kind));
            if (want != null) {
                for (String id : want.keySet()) {
                    ResourceLocation f = fileOf(id);
                    if (f == null || !originals.containsKey(f)) warnings.add("낚시 " + kind.label() + " " + id + ": 서버에 없어 건너뜀");
                }
            }
            for (Map.Entry<ResourceLocation, JsonObject> e : originals.entrySet()) {
                Double w = want == null ? null : GunBalance.num(want, idOf(e.getKey()));
                JsonObject copy = w == null ? null : applyWeight(kind, e.getValue(), w);
                if (copy == null) {
                    if (PackFiles.delete(pack, e.getKey())) deleted++;
                } else if (PackFiles.write(pack, e.getKey(), copy)) {
                    written++;
                }
            }
        }
        JsonObject wantTables = GunBalance.obj(desired, TABLES);
        if (wantTables != null) {
            for (String id : wantTables.keySet()) {
                if (!o.tables().containsKey(id)) warnings.add("낚시 내용물 " + id + ": 서버에 없는 표라 건너뜀");
            }
        }
        for (Map.Entry<String, JsonObject> e : new TreeMap<>(o.tables()).entrySet()) {
            ResourceLocation file = tableFile(e.getKey());
            if (file == null) continue;
            JsonObject edits = wantTables == null ? null : GunBalance.obj(wantTables, e.getKey());
            JsonObject copy = edits == null ? null : applyTable(e.getKey(), e.getValue(), edits, itemExists, warnings);
            if (copy == null) {
                if (PackFiles.delete(pack, file)) deleted++;
            } else if (PackFiles.write(pack, file, copy)) {
                written++;
            }
        }
        return new Outcome(written, deleted, warnings);
    }

    /** Original with the weight replaced (0 also drops the quality), or null when it equals the original. */
    static JsonObject applyWeight(Kind kind, JsonObject orig, double weight) {
        double base = orZero(GunBalance.num(orig, kind.weightField()));
        if (Math.abs(base - weight) <= 1e-9) return null;
        JsonObject copy = orig.deepCopy();
        copy.addProperty(kind.weightField(), weight);
        if (weight <= 0) copy.remove(kind.qualityField());
        return copy;
    }

    /**
     * Original loot table with entry weights replaced (0 = entry removed) and items appended, or null when nothing
     * ends up different. A table that would have no entries left is not written (warning).
     */
    static JsonObject applyTable(String id, JsonObject orig, JsonObject edits, Predicate<ResourceLocation> itemExists,
                                 List<String> warnings) {
        if (entriesOf(orig) == null) {
            warnings.add("낚시 내용물 " + id + ": 편집할 수 없는 형식의 표라 건너뜀");
            return null;
        }
        JsonObject copy = orig.deepCopy();
        JsonArray entries = entriesOf(copy);
        JsonObject weights = GunBalance.obj(edits, WEIGHTS);
        JsonArray out = new JsonArray();
        for (int i = 0; i < entries.size(); i++) {
            JsonElement entry = entries.get(i);
            Double w = weights == null ? null : GunBalance.num(weights, String.valueOf(i));
            if (w == null || !entry.isJsonObject()) {
                out.add(entry);
            } else if (w >= 1) {
                entry.getAsJsonObject().addProperty("weight", (int) Math.rint(w));
                out.add(entry);
            }
        }
        if (weights != null) {
            for (String key : weights.keySet()) {
                int i = parseIndex(key);
                if (i < 0 || i >= entries.size()) warnings.add("낚시 내용물 " + id + ": " + key + "번 항목이 원본에 없어 건너뜀");
            }
        }
        JsonElement added = edits.get(ADDED);
        if (added != null && added.isJsonArray()) {
            for (JsonElement x : added.getAsJsonArray()) {
                if (!x.isJsonObject()) continue;
                JsonObject a = x.getAsJsonObject();
                String item = str(a, "item");
                ResourceLocation itemId = item == null ? null : ResourceLocation.tryParse(item);
                if (itemId == null || !itemExists.test(itemId)) {
                    warnings.add("낚시 내용물 " + id + ": 추가한 아이템 " + item + " 이(가) 서버에 없어 건너뜀");
                    continue;
                }
                out.add(addedEntry(itemId.toString(), (int) Math.rint(orZero(GunBalance.num(a, "weight"))),
                        (int) Math.rint(orOne(GunBalance.num(a, "min"))), (int) Math.rint(orOne(GunBalance.num(a, "max")))));
            }
        }
        if (out.isEmpty()) {
            warnings.add("낚시 내용물 " + id + ": 남는 아이템이 없어 건너뜀 — 아예 안 나오게 하려면 묶음 가중치를 0 으로 하세요");
            return null;
        }
        copy.getAsJsonArray("pools").get(0).getAsJsonObject().add("entries", out);
        return tableDiff(orig, copy).size() == 0 ? null : copy;
    }

    static JsonObject addedEntry(String item, int weight, int min, int max) {
        JsonObject e = new JsonObject();
        e.addProperty("type", "minecraft:item");
        e.addProperty("name", item);
        e.addProperty("weight", Math.max(1, weight));
        if (min != 1 || max != 1) {
            JsonObject fn = new JsonObject();
            fn.addProperty("function", "minecraft:set_count");
            fn.addProperty("add", false);
            if (min == max) {
                fn.addProperty("count", min);
            } else {
                JsonObject count = new JsonObject();
                count.addProperty("type", "minecraft:uniform");
                count.addProperty("min", min);
                count.addProperty("max", max);
                fn.add("count", count);
            }
            JsonArray fns = new JsonArray();
            fns.add(fn);
            e.add("functions", fns);
        }
        return e;
    }

    /**
     * What {@code copy} changes: {@code weights} (original index -> weight, 0 = removed) and {@code added} items.
     * Copy entries are matched to the original ones in order, ignoring the weight; whatever matches nothing is an
     * added item.
     */
    static JsonObject tableDiff(JsonObject orig, JsonObject copy) {
        JsonObject out = new JsonObject();
        JsonArray a = entriesOf(orig);
        JsonArray b = entriesOf(copy);
        if (a == null || b == null) return out;
        JsonObject weights = new JsonObject();
        JsonArray added = new JsonArray();
        boolean[] matched = new boolean[a.size()];
        int from = 0;
        for (JsonElement c : b) {
            int hit = -1;
            for (int i = from; i < a.size() && hit < 0; i++) {
                if (sameEntry(a.get(i), c)) hit = i;
            }
            if (hit < 0) {
                added.add(addedRow(c));
                continue;
            }
            matched[hit] = true;
            from = hit + 1;
            int wa = entryWeight(a.get(hit).getAsJsonObject());
            int wb = entryWeight(c.getAsJsonObject());
            if (wa != wb) weights.addProperty(String.valueOf(hit), wb);
        }
        for (int i = 0; i < matched.length; i++) {
            if (!matched[i]) weights.addProperty(String.valueOf(i), 0);
        }
        if (weights.size() > 0) out.add(WEIGHTS, weights);
        if (!added.isEmpty()) out.add(ADDED, added);
        return out;
    }

    private static boolean sameEntry(JsonElement a, JsonElement b) {
        if (!a.isJsonObject() || !b.isJsonObject()) return false;
        JsonObject x = a.getAsJsonObject().deepCopy();
        JsonObject y = b.getAsJsonObject().deepCopy();
        x.remove("weight");
        y.remove("weight");
        return x.equals(y);
    }

    private static JsonObject addedRow(JsonElement entry) {
        JsonObject row = new JsonObject();
        JsonObject e = entry.isJsonObject() ? entry.getAsJsonObject() : new JsonObject();
        String name = str(e, "name");
        row.addProperty("item", name == null ? "" : name);
        row.addProperty("weight", entryWeight(e));
        int min = 1;
        int max = 1;
        JsonElement fns = e.get("functions");
        if (fns != null && fns.isJsonArray()) {
            for (JsonElement f : fns.getAsJsonArray()) {
                if (!f.isJsonObject() || !"minecraft:set_count".equals(str(f.getAsJsonObject(), "function"))) continue;
                JsonElement count = f.getAsJsonObject().get("count");
                Double n = GunBalance.number(count);
                if (n != null) {
                    min = max = (int) Math.rint(n);
                } else if (count != null && count.isJsonObject()) {
                    min = (int) Math.rint(orOne(GunBalance.num(count.getAsJsonObject(), "min")));
                    max = (int) Math.rint(orOne(GunBalance.num(count.getAsJsonObject(), "max")));
                }
            }
        }
        row.addProperty("min", min);
        row.addProperty("max", max);
        return row;
    }

    /** The entry list of a loot table with exactly one pool (the only shape the editor changes), else null. */
    static JsonArray entriesOf(JsonObject table) {
        JsonElement pools = table == null ? null : table.get("pools");
        if (pools == null || !pools.isJsonArray() || pools.getAsJsonArray().size() != 1) return null;
        JsonElement pool = pools.getAsJsonArray().get(0);
        if (!pool.isJsonObject()) return null;
        JsonElement entries = pool.getAsJsonObject().get("entries");
        if (entries == null || !entries.isJsonArray() || entries.getAsJsonArray().isEmpty()) return null;
        for (JsonElement e : entries.getAsJsonArray()) if (!e.isJsonObject()) return null;
        return entries.getAsJsonArray();
    }

    /** Loot entry weight; the game's default is 1. */
    private static int entryWeight(JsonObject entry) {
        Double w = GunBalance.num(entry, "weight");
        return w == null ? 1 : (int) Math.rint(w);
    }

    // ---- small helpers --------------------------------------------------------------------------------------

    private static boolean isWhole(Double n, double min, double max) {
        return n != null && n >= min && n <= max && n == Math.rint(n);
    }

    private static int parseIndex(String key) {
        try {
            return Integer.parseInt(key);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static double orZero(Double d) {
        return d == null ? 0 : d;
    }

    private static double orOne(Double d) {
        return d == null ? 1 : d;
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() ? e.getAsString() : null;
    }

    private static List<String> strings(JsonElement e) {
        List<String> out = new ArrayList<>();
        if (e != null && e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) {
                if (x.isJsonPrimitive() && x.getAsJsonPrimitive().isString()) out.add(x.getAsString());
            }
        } else if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) {
            out.add(e.getAsString());
        }
        return out;
    }

    private static boolean contains(JsonArray arr, String s) {
        for (JsonElement e : arr) if (e.isJsonPrimitive() && e.getAsString().equals(s)) return true;
        return false;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }

    private static <V> Map<ResourceLocation, V> sorted(Map<ResourceLocation, V> m) {
        Map<ResourceLocation, V> t = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        t.putAll(m);
        return new LinkedHashMap<>(t);
    }
}
