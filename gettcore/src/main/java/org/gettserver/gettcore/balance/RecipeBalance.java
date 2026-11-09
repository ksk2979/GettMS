package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;

/**
 * Blocking the crafting of mod equipment: a copy of the recipe file ({@code data/<ns>/recipe/<path>.json}, recipe id
 * {@code <ns>:<path>}) with a NeoForge load condition that is always false. NeoForge's RecipeManager parses every
 * recipe with {@code Recipe.CONDITIONAL_CODEC} and skips it ("conditions were not met") when any entry of
 * {@code "neoforge:conditions"} fails; {@code neoforge:false} ({@code FalseCondition}) always fails. Unblocking =
 * deleting the copy.
 *
 * <p>Listed: recipes (any type) whose result item is not in the {@code minecraft} namespace and is equipment (see
 * {@link #classify}); the server supplies the item kind ({@code kindOf}: item id -> kind or null). Recipes we already
 * blocked stay listed even when they no longer count as equipment, so they can be unblocked.
 *
 * <p>Editor state: {@code datapack.recipes = {recipe id: true}} (blocked ones). Catalog: rows {id, item, mod, kind,
 * type}, mod = the result item's namespace.
 */
public final class RecipeBalance {
    public static final String FOLDER = "recipe";
    public static final String CONDITIONS = "neoforge:conditions";
    public static final String FALSE_TYPE = "neoforge:false";

    public static final String WEAPON = "weapon";
    public static final String ARMOR = "armor";
    public static final String SHIELD = "shield";
    public static final String GUN = "gun";
    public static final String OTHER = "other";

    /** What the server knows about a result item. */
    public record Facts(boolean gun, boolean shieldItem, boolean shieldAbility, boolean armorItem, boolean ranged,
                        boolean attackDamage, boolean armorAttr) {}

    public record Row(ResourceLocation file, String id, String item, String kind, String type) {}

    public record Outcome(int written, int deleted, List<String> warnings) {
        public boolean changed() {
            return written + deleted > 0;
        }
    }

    private RecipeBalance() {}

    /**
     * Equipment kind, or null when the item is not equipment. Gun first (NTGL weapon item), then shield (ShieldItem),
     * armor (ArmorItem), weapon (anything with an attack damage modifier — swords, axes, mod blades — or a
     * bow/crossbow/trident), shield again for other items that can block but deal no damage (Epic Knights blades and
     * polearms can block too, so the block ability alone must not make them shields), then "other" for items that
     * only give armor through attributes.
     */
    public static String classify(Facts f) {
        if (f.gun()) return GUN;
        if (f.shieldItem()) return SHIELD;
        if (f.armorItem()) return ARMOR;
        if (f.ranged() || f.attackDamage()) return WEAPON;
        if (f.shieldAbility()) return SHIELD;
        if (f.armorAttr()) return OTHER;
        return null;
    }

    /** {@code cgs:recipe/mechanical_crafting/revolver.json} -> {@code cgs:mechanical_crafting/revolver}; null otherwise. */
    public static String idOf(ResourceLocation file) {
        String p = file.getPath();
        if (!p.startsWith(FOLDER + "/") || !p.endsWith(".json") || p.length() <= FOLDER.length() + 6) return null;
        return file.getNamespace() + ":" + p.substring(FOLDER.length() + 1, p.length() - 5);
    }

    public static ResourceLocation fileOf(String id) {
        ResourceLocation r = ResourceLocation.tryParse(id);
        return r == null ? null : ResourceLocation.tryBuild(r.getNamespace(), FOLDER + "/" + r.getPath() + ".json");
    }

    /**
     * Result item ids in the formats the installed mods use: {@code "result": {"id"|"item": ...}} (crafting, smithing,
     * cooking, NTGL workbench, Create mechanical crafting), {@code "result": "<id>"}, {@code "results": [...]} (Create
     * processing / sequenced assembly; fluid entries with "amount" are skipped) and Farmer's Delight cutting
     * {@code "result": [{"item": {"id": ...} | "<id>"}]}. Order kept (main result first).
     */
    public static List<String> resultIds(JsonObject recipe) {
        List<String> out = new ArrayList<>();
        addResults(recipe.get("result"), out);
        addResults(recipe.get("results"), out);
        return out;
    }

    private static void addResults(JsonElement e, List<String> out) {
        if (e == null) return;
        if (e.isJsonArray()) {
            for (JsonElement x : e.getAsJsonArray()) addResult(x, out);
        } else {
            addResult(e, out);
        }
    }

    private static void addResult(JsonElement e, List<String> out) {
        if (e instanceof JsonPrimitive p) {
            if (p.isString()) out.add(p.getAsString());
            return;
        }
        if (!(e instanceof JsonObject o) || o.has("amount")) return;
        JsonElement id = o.has("id") ? o.get("id") : o.get("item");
        if (id instanceof JsonPrimitive p && p.isString()) out.add(p.getAsString());
        else if (id instanceof JsonObject inner) addResult(inner, out);
    }

    /** The first result that is a non-vanilla equipment item, or null. */
    public static String equipmentResult(List<String> results, Function<String, String> kindOf) {
        for (String id : results) {
            ResourceLocation r = ResourceLocation.tryParse(id);
            if (r == null || r.getNamespace().equals(ResourceLocation.DEFAULT_NAMESPACE)) continue;
            if (kindOf.apply(r.toString()) != null) return r.toString();
        }
        return null;
    }

    /** Original + a {@code neoforge:false} condition (existing conditions kept); every other field unchanged. */
    public static JsonObject disabledCopy(JsonObject orig) {
        JsonObject copy = orig.deepCopy();
        if (isDisabled(copy)) return copy;
        JsonArray conds = copy.get(CONDITIONS) instanceof JsonArray a ? a : new JsonArray();
        JsonObject f = new JsonObject();
        f.addProperty("type", FALSE_TYPE);
        conds.add(f);
        copy.add(CONDITIONS, conds);
        return copy;
    }

    /** Whether the file carries our always-false condition (= the recipe is not loaded). */
    public static boolean isDisabled(JsonObject json) {
        if (!(json.get(CONDITIONS) instanceof JsonArray a)) return false;
        for (JsonElement c : a) {
            if (c instanceof JsonObject o && o.get("type") instanceof JsonPrimitive t && FALSE_TYPE.equals(t.getAsString())) {
                return true;
            }
        }
        return false;
    }

    /** Listed recipes, sorted by recipe id: mod equipment recipes plus any recipe our pack holds a copy of. */
    public static Map<ResourceLocation, Row> rows(Map<ResourceLocation, JsonObject> originals,
                                                  Function<String, String> kindOf, Path pack) {
        Set<ResourceLocation> copies = new HashSet<>(PackFiles.listFolder(pack, FOLDER));
        Map<ResourceLocation, Row> out = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        for (Map.Entry<ResourceLocation, JsonObject> e : originals.entrySet()) {
            String id = idOf(e.getKey());
            if (id == null || e.getValue() == null) continue;
            List<String> results = resultIds(e.getValue());
            String item = equipmentResult(results, kindOf);
            String kind = item == null ? null : kindOf.apply(item);
            if (item == null) {
                if (!copies.contains(e.getKey())) continue;
                item = results.isEmpty() ? "" : results.get(0);
                kind = OTHER;
            }
            JsonElement type = e.getValue().get("type");
            out.put(e.getKey(), new Row(e.getKey(), id, item, kind,
                    type instanceof JsonPrimitive p ? p.getAsString() : ""));
        }
        return out;
    }

    public static JsonArray catalog(Map<ResourceLocation, Row> rows) {
        JsonArray out = new JsonArray();
        for (Row r : rows.values()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", r.id());
            o.addProperty("item", r.item());
            ResourceLocation item = ResourceLocation.tryParse(r.item());
            o.addProperty("mod", item == null ? "" : item.getNamespace());
            o.addProperty("kind", r.kind());
            o.addProperty("type", r.type());
            out.add(o);
        }
        return out;
    }

    /** Blocked recipes ({id: true}) among the listed ones. */
    public static JsonObject state(Map<ResourceLocation, JsonObject> originals, Function<String, String> kindOf, Path pack) {
        JsonObject out = new JsonObject();
        for (Row r : rows(originals, kindOf, pack).values()) {
            if (PackFiles.read(pack, r.file()).filter(RecipeBalance::isDisabled).isPresent()) out.addProperty(r.id(), true);
        }
        return out;
    }

    /** Errors for the save: recipe id -> true/false. */
    public static List<String> validate(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            if (fileOf(e.getKey()) == null) {
                errs.add("제작법 " + e.getKey() + ": 잘못된 id");
            } else if (!(e.getValue() instanceof JsonPrimitive p) || !p.isBoolean()) {
                errs.add("제작법 " + e.getKey() + ": true/false 여야 합니다");
            }
        }
        return errs;
    }

    /**
     * Makes our copies match {@code desired} for every listed recipe: blocked -> disabled copy, otherwise our disabled
     * copy is deleted (a copy without our condition is never deleted). Requests for unlisted recipes are skipped with a
     * warning. Call {@link #validate} first.
     */
    public static Outcome reconcile(Map<ResourceLocation, JsonObject> originals, Function<String, String> kindOf,
                                    Path pack, JsonObject desired) throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<ResourceLocation, Row> rows = rows(originals, kindOf, pack);
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            ResourceLocation f = fileOf(e.getKey());
            if (!blocked(e.getValue())) continue;
            if (f == null || !originals.containsKey(f)) {
                warnings.add("제작법 " + e.getKey() + ": 서버에 없는 제작법이라 건너뜀");
            } else if (!rows.containsKey(f)) {
                warnings.add("제작법 " + e.getKey() + ": 모드 장비 제작법이 아니라 건너뜀");
            }
        }
        int written = 0;
        int deleted = 0;
        for (Row r : rows.values()) {
            if (blocked(desired.get(r.id()))) {
                if (PackFiles.write(pack, r.file(), disabledCopy(originals.get(r.file())))) written++;
            } else if (PackFiles.read(pack, r.file()).filter(RecipeBalance::isDisabled).isPresent()
                    && PackFiles.delete(pack, r.file())) {
                deleted++;
            }
        }
        return new Outcome(written, deleted, warnings);
    }

    private static boolean blocked(JsonElement v) {
        return v instanceof JsonPrimitive p && p.isBoolean() && p.getAsBoolean();
    }
}
