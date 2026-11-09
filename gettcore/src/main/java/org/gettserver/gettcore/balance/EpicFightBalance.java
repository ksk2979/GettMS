package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import net.minecraft.resources.ResourceLocation;

/**
 * Epic Fight weapon attributes ({@code data/<ns>/capabilities/weapons/<item path>.json}, item {@code <ns>:<item path>}).
 * Files look like {@code {"type": "epicfight:sword", "attributes": {"common": {"impact": 1.1, "max_strikes": 1}}}};
 * styles are {@code common}, {@code one_hand}, {@code two_hand} (others are kept as found). Epic Fight itself skips
 * paths containing "types" or "item_keyword" (weapon type / keyword files), and so do we.
 *
 * <p>A copy is the mod's original with only the edited numbers replaced: every other field (type, collider, combos,
 * other styles) stays. Values can be added to any style the original has, and to {@code common} even when the
 * original has no attributes at all. Editor ids are item ids, e.g. {@code epicfight:diamond_dagger}.
 */
public final class EpicFightBalance {
    public static final String FOLDER = "capabilities/weapons";
    public static final String ATTRIBUTES = "attributes";
    public static final String COMMON = "common";
    private static final Pattern STYLE = Pattern.compile("[a-z0-9_]{1,32}");

    /** One attribute key as Epic Fight's ItemCapabilityReloadListener reads it. */
    public record Field(String key, boolean integer, double min, double max) {}

    // Keys and types from ItemCapabilityReloadListener.deserializeAttributes (Epic Fight 21.17):
    // damage_bonus / speed_bonus are attack damage / attack speed modifiers, the others Epic Fight attributes.
    public static final List<Field> FIELDS = List.of(
            new Field("damage_bonus", false, -100, 1000),
            new Field("speed_bonus", false, -4, 4),
            new Field("armor_negation", false, 0, 100),
            new Field("impact", false, -10, 100),
            new Field("max_strikes", true, 0, 50));

    public record Outcome(int written, int deleted, List<String> warnings) {
        public boolean changed() {
            return written + deleted > 0;
        }
    }

    private EpicFightBalance() {}

    /** {@code wom:capabilities/weapons/solar.json} -> {@code wom:solar}; null for non-weapon files. */
    public static String itemOf(ResourceLocation file) {
        String p = file.getPath();
        if (!p.startsWith(FOLDER + "/") || !p.endsWith(".json")) return null;
        String rest = p.substring(FOLDER.length() + 1, p.length() - 5);
        // Same skip rule as Epic Fight (checked on the path below "capabilities/").
        if (rest.isEmpty() || p.substring("capabilities/".length()).contains("types") || rest.contains("item_keyword")) {
            return null;
        }
        return file.getNamespace() + ":" + rest;
    }

    public static ResourceLocation fileOf(String item) {
        ResourceLocation r = ResourceLocation.tryParse(item);
        return r == null ? null : ResourceLocation.tryBuild(r.getNamespace(), FOLDER + "/" + r.getPath() + ".json");
    }

    /** Weapon files only (see {@link #itemOf}), with an object root and attributes absent or an object. */
    static Map<ResourceLocation, JsonObject> weapons(Map<ResourceLocation, JsonObject> originals) {
        Map<ResourceLocation, JsonObject> out = new TreeMap<>(Comparator.comparing(ResourceLocation::toString));
        originals.forEach((k, v) -> {
            if (itemOf(k) == null || v == null) return;
            JsonElement a = v.get(ATTRIBUTES);
            if (a != null && !a.isJsonObject()) return;
            out.put(k, v);
        });
        return out;
    }

    /** Rows: {id, mod, type, styles: {style: {key: value}}} with only the values the original has. Sorted by id. */
    public static JsonArray catalog(Map<ResourceLocation, JsonObject> originals) {
        JsonArray out = new JsonArray();
        for (Map.Entry<ResourceLocation, JsonObject> e : weapons(originals).entrySet()) {
            JsonObject orig = e.getValue();
            JsonObject row = new JsonObject();
            row.addProperty("id", itemOf(e.getKey()));
            row.addProperty("mod", e.getKey().getNamespace());
            JsonElement type = orig.get("type");
            row.addProperty("type", type != null && type.isJsonPrimitive() ? type.getAsString() : "");
            JsonObject styles = new JsonObject();
            JsonObject attrs = GunBalance.obj(orig, ATTRIBUTES);
            if (attrs != null) {
                for (String style : attrs.keySet()) {
                    JsonObject s = GunBalance.obj(attrs, style);
                    if (s == null) continue;
                    JsonObject vals = new JsonObject();
                    for (Field f : FIELDS) {
                        Double v = GunBalance.num(s, f.key());
                        if (v != null) vals.addProperty(f.key(), v);
                    }
                    styles.add(style, vals);
                }
            }
            row.add("styles", styles);
            out.add(row);
        }
        return out;
    }

    /** Current overrides read back from our copies: per item, per style, the values that differ or were added. */
    public static JsonObject state(Map<ResourceLocation, JsonObject> originals, Path pack) {
        JsonObject out = new JsonObject();
        for (Map.Entry<ResourceLocation, JsonObject> e : weapons(originals).entrySet()) {
            PackFiles.read(pack, e.getKey()).ifPresent(copy -> {
                JsonObject d = diff(e.getValue(), copy);
                if (d.size() > 0) out.add(itemOf(e.getKey()), d);
            });
        }
        return out;
    }

    /** Errors for the save (whole save is refused). {@code desired}: item -> style -> key -> number. */
    public static List<String> validate(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            String id = e.getKey();
            if (!e.getValue().isJsonObject()) {
                errs.add("Epic Fight " + id + ": 객체여야 합니다");
                continue;
            }
            for (Map.Entry<String, JsonElement> s : e.getValue().getAsJsonObject().entrySet()) {
                String style = s.getKey();
                if (!STYLE.matcher(style).matches()) {
                    errs.add("Epic Fight " + id + ": 잘못된 스타일 이름 " + style);
                    continue;
                }
                if (!s.getValue().isJsonObject()) {
                    errs.add("Epic Fight " + id + " › " + style + ": 객체여야 합니다");
                    continue;
                }
                for (Map.Entry<String, JsonElement> v : s.getValue().getAsJsonObject().entrySet()) {
                    Field f = field(v.getKey());
                    if (f == null) {
                        errs.add("Epic Fight " + id + " › " + style + ": 모르는 항목 " + v.getKey());
                        continue;
                    }
                    Double n = GunBalance.number(v.getValue());
                    if (n == null || n < f.min() || n > f.max() || (f.integer() && n != Math.rint(n))) {
                        errs.add("Epic Fight " + id + " › " + style + " › " + f.key() + ": " + fmt(f.min()) + "~"
                                + fmt(f.max()) + (f.integer() ? " 사이 정수" : " 사이 숫자") + "여야 합니다");
                    }
                }
            }
        }
        return errs;
    }

    /**
     * Makes our copies match {@code desired} for every weapon file in {@code originals}: a copy where something
     * differs from the original, no copy otherwise. Copies of other files are not touched. Call {@link #validate} first.
     */
    public static Outcome reconcile(Map<ResourceLocation, JsonObject> originals, Path pack, JsonObject desired)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        Map<ResourceLocation, JsonObject> weapons = weapons(originals);
        for (String id : desired.keySet()) {
            ResourceLocation f = fileOf(id);
            if (f == null || !weapons.containsKey(f)) warnings.add("Epic Fight " + id + ": 서버에 없는 무기 파일이라 건너뜀");
        }
        int written = 0;
        int deleted = 0;
        for (Map.Entry<ResourceLocation, JsonObject> e : weapons.entrySet()) {
            String id = itemOf(e.getKey());
            JsonElement want = desired.get(id);
            JsonObject copy = want != null && want.isJsonObject()
                    ? apply(id, e.getValue(), want.getAsJsonObject(), warnings) : null;
            if (copy == null) {
                if (PackFiles.delete(pack, e.getKey())) deleted++;
            } else if (PackFiles.write(pack, e.getKey(), copy)) {
                written++;
            }
        }
        return new Outcome(written, deleted, warnings);
    }

    /** Original + edits, or null when nothing ends up different from the original. */
    static JsonObject apply(String id, JsonObject orig, JsonObject edits, List<String> warnings) {
        JsonObject copy = orig.deepCopy();
        for (Map.Entry<String, JsonElement> s : edits.entrySet()) {
            if (!s.getValue().isJsonObject()) continue;
            String style = s.getKey();
            for (Map.Entry<String, JsonElement> v : s.getValue().getAsJsonObject().entrySet()) {
                Field f = field(v.getKey());
                Double n = GunBalance.number(v.getValue());
                if (f == null || n == null) continue;
                JsonObject st = styleFor(copy, style);
                if (st == null) {
                    warnings.add("Epic Fight " + id + ": 원본에 " + style + " 스타일이 없어 건너뜀 (common 만 새로 넣을 수 있음)");
                    break;
                }
                if (f.integer()) st.addProperty(f.key(), (long) Math.rint(n));
                else st.addProperty(f.key(), n);
            }
        }
        return diff(orig, copy).size() == 0 ? null : copy;
    }

    /** The style object to write into: existing, or a new {@code common} (attributes created when missing). */
    private static JsonObject styleFor(JsonObject copy, String style) {
        JsonObject attrs = GunBalance.obj(copy, ATTRIBUTES);
        JsonObject st = GunBalance.obj(attrs, style);
        if (st != null) return st;
        if (!COMMON.equals(style)) return null;
        if (attrs == null) {
            attrs = new JsonObject();
            copy.add(ATTRIBUTES, attrs);
        }
        st = new JsonObject();
        attrs.add(COMMON, st);
        return st;
    }

    /** Values of {@code copy} that differ from {@code orig} or are not in it: style -> key -> value. */
    static JsonObject diff(JsonObject orig, JsonObject copy) {
        JsonObject out = new JsonObject();
        JsonObject oa = GunBalance.obj(orig, ATTRIBUTES);
        JsonObject ca = GunBalance.obj(copy, ATTRIBUTES);
        if (ca == null) return out;
        Set<String> styles = new LinkedHashSet<>(ca.keySet());
        for (String style : styles) {
            JsonObject cs = GunBalance.obj(ca, style);
            if (cs == null) continue;
            JsonObject os = GunBalance.obj(oa, style);
            JsonObject d = new JsonObject();
            for (Field f : FIELDS) {
                Double b = GunBalance.num(cs, f.key());
                if (b == null) continue;
                Double a = GunBalance.num(os, f.key());
                if (a == null || Math.abs(a - b) > 1e-9) {
                    if (f.integer()) d.addProperty(f.key(), (long) Math.rint(b));
                    else d.addProperty(f.key(), b);
                }
            }
            if (d.size() > 0) out.add(style, d);
        }
        return out;
    }

    private static Field field(String key) {
        for (Field f : FIELDS) if (f.key().equals(key)) return f;
        return null;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
