package org.gettserver.gettcore.balance;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * Gun numbers of NTGL-based weapon files ({@code data/<ns>/weapons/**.json}: NTGL and Create: Gunsmithing).
 * The editor works in seconds for tick values; a copy is the mod's original file with only the edited numbers
 * replaced (unknown fields stay). Ids are file locations without ".json", e.g. {@code cgs:weapons/revolver}.
 */
public final class GunBalance {
    public static final String FOLDER = "weapons";
    public static final String DAMAGE = "damage";

    /** One editable number in {@code general}. ticks: shown in seconds, stored as whole ticks. */
    public record Field(String key, boolean ticks, boolean integer, double min, double max) {}

    public static final List<Field> FIELDS = List.of(
            new Field("reloadTime", true, true, 0, 600),
            new Field("rate", true, true, 0.05, 60),
            new Field("maxAmmo", false, true, 1, 100000),
            new Field("spread", false, false, 0, 180),
            new Field("recoilAngle", false, false, -180, 180),
            new Field("equipTime", true, true, 0, 60));
    public static final double DAMAGE_MAX = 100000;

    public record Outcome(int written, int deleted, List<String> warnings) {
        public boolean changed() {
            return written + deleted > 0;
        }
    }

    private GunBalance() {}

    public static String idOf(ResourceLocation file) {
        String p = file.getPath();
        return file.getNamespace() + ":" + (p.endsWith(".json") ? p.substring(0, p.length() - 5) : p);
    }

    /** File location for an editor id, or null when the id is not a valid resource location. */
    public static ResourceLocation fileOf(String id) {
        return ResourceLocation.tryParse(id + ".json");
    }

    /** A weapon file the tab can edit: has a {@code general} object with a known number, or bullet damage. */
    public static boolean isGun(JsonObject o) {
        JsonObject g = obj(o, "general");
        if (g == null) return false;
        for (Field f : FIELDS) if (num(g, f.key()) != null) return true;
        return !ammoDamage(o).isEmpty();
    }

    /** Catalog rows (original values, editor units), sorted by id. Non-gun files are left out. */
    public static JsonArray catalog(Map<ResourceLocation, JsonObject> originals) {
        JsonArray out = new JsonArray();
        for (Map.Entry<ResourceLocation, JsonObject> e : sorted(originals).entrySet()) {
            JsonObject orig = e.getValue();
            if (!isGun(orig)) continue;
            ResourceLocation file = e.getKey();
            String path = file.getPath();
            String base = path.substring(path.lastIndexOf('/') + 1).replace(".json", "");
            JsonObject row = new JsonObject();
            row.addProperty("id", idOf(file));
            row.addProperty("mod", file.getNamespace());
            row.addProperty("item", file.getNamespace() + ":" + base);
            JsonObject g = obj(orig, "general");
            for (Field f : FIELDS) {
                Double v = num(g, f.key());
                if (v != null) row.addProperty(f.key(), toEditor(f, v));
            }
            JsonArray ammo = new JsonArray();
            ammoDamage(orig).forEach((id, dmg) -> {
                JsonObject a = new JsonObject();
                a.addProperty("id", id);
                a.addProperty(DAMAGE, dmg);
                ammo.add(a);
            });
            row.add("ammo", ammo);
            out.add(row);
        }
        return out;
    }

    /** Current overrides read back from our copies: per id only the values that differ from the original. */
    public static JsonObject state(Map<ResourceLocation, JsonObject> originals, Path pack) {
        JsonObject out = new JsonObject();
        for (Map.Entry<ResourceLocation, JsonObject> e : sorted(originals).entrySet()) {
            if (!isGun(e.getValue())) continue;
            PackFiles.read(pack, e.getKey()).ifPresent(copy -> {
                JsonObject o = diff(e.getValue(), copy);
                if (o.size() > 0) out.add(idOf(e.getKey()), o);
            });
        }
        return out;
    }

    /** Errors for the save (whole save is refused). Values are in editor units. */
    public static List<String> validate(JsonObject desired) {
        List<String> errs = new ArrayList<>();
        for (Map.Entry<String, JsonElement> e : desired.entrySet()) {
            String id = e.getKey();
            if (!e.getValue().isJsonObject()) {
                errs.add("총 " + id + ": 객체여야 합니다");
                continue;
            }
            for (Map.Entry<String, JsonElement> v : e.getValue().getAsJsonObject().entrySet()) {
                if (v.getKey().equals(DAMAGE)) {
                    if (!v.getValue().isJsonObject()) {
                        errs.add("총 " + id + " › 탄 피해: 객체여야 합니다");
                        continue;
                    }
                    for (Map.Entry<String, JsonElement> d : v.getValue().getAsJsonObject().entrySet()) {
                        Double n = number(d.getValue());
                        if (n == null || n < 0 || n > DAMAGE_MAX) {
                            errs.add("총 " + id + " › " + d.getKey() + " 피해: 0~" + (int) DAMAGE_MAX + " 사이 숫자여야 합니다");
                        }
                    }
                    continue;
                }
                Field f = field(v.getKey());
                if (f == null) {
                    errs.add("총 " + id + ": 모르는 항목 " + v.getKey());
                    continue;
                }
                Double n = number(v.getValue());
                if (n == null || n < f.min() || n > f.max() || (f.integer() && !f.ticks() && n != Math.rint(n))) {
                    errs.add("총 " + id + " › " + f.key() + ": " + fmt(f.min()) + "~" + fmt(f.max())
                            + (f.integer() && !f.ticks() ? " 사이 정수" : " 사이 숫자") + "여야 합니다");
                }
            }
        }
        return errs;
    }

    /**
     * Makes our copies match {@code desired} (id -> overrides, editor units) for every gun in {@code originals}:
     * a copy where something differs from the original, no copy otherwise. Copies of files that are not in
     * {@code originals} are not touched. Call {@link #validate} first.
     */
    public static Outcome reconcile(Map<ResourceLocation, JsonObject> originals, Path pack, JsonObject desired)
            throws IOException {
        List<String> warnings = new ArrayList<>();
        int written = 0;
        int deleted = 0;
        Map<ResourceLocation, JsonObject> guns = new TreeMap<>(java.util.Comparator.comparing(ResourceLocation::toString));
        sorted(originals).forEach((k, v) -> {
            if (isGun(v)) guns.put(k, v);
        });
        for (String id : desired.keySet()) {
            ResourceLocation f = fileOf(id);
            if (f == null || !guns.containsKey(f)) warnings.add("총 " + id + ": 서버에 없는 총이라 건너뜀");
        }
        for (Map.Entry<ResourceLocation, JsonObject> e : guns.entrySet()) {
            String id = idOf(e.getKey());
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
        JsonObject g = obj(copy, "general");
        for (Map.Entry<String, JsonElement> v : edits.entrySet()) {
            if (v.getKey().equals(DAMAGE)) {
                if (!v.getValue().isJsonObject()) continue;
                for (Map.Entry<String, JsonElement> d : v.getValue().getAsJsonObject().entrySet()) {
                    JsonObject proj = projectile(copy, d.getKey());
                    Double n = number(d.getValue());
                    if (proj == null || num(proj, DAMAGE) == null) {
                        warnings.add("총 " + id + ": 탄 " + d.getKey() + " 의 피해 값이 원본에 없어 건너뜀");
                    } else if (n != null) {
                        proj.addProperty(DAMAGE, n);
                    }
                }
                continue;
            }
            Field f = field(v.getKey());
            Double n = number(v.getValue());
            if (f == null || n == null) continue;
            if (g == null || num(g, f.key()) == null) {
                warnings.add("총 " + id + ": " + f.key() + " 값이 원본에 없어 건너뜀");
                continue;
            }
            if (f.ticks()) g.addProperty(f.key(), PackFiles.secondsToTicks(n));
            else if (f.integer()) g.addProperty(f.key(), (long) Math.rint(n));
            else g.addProperty(f.key(), n);
        }
        return diff(orig, copy).size() == 0 ? null : copy;
    }

    /** Values of {@code copy} (editor units) that differ from {@code orig}. */
    static JsonObject diff(JsonObject orig, JsonObject copy) {
        JsonObject out = new JsonObject();
        JsonObject og = obj(orig, "general");
        JsonObject cg = obj(copy, "general");
        if (og != null && cg != null) {
            for (Field f : FIELDS) {
                Double a = num(og, f.key());
                Double b = num(cg, f.key());
                if (a == null || b == null) continue;
                if (f.ticks() ? PackFiles.secondsToTicks(toEditor(f, a)) != PackFiles.secondsToTicks(toEditor(f, b))
                        : Math.abs(a - b) > 1e-9) {
                    out.addProperty(f.key(), toEditor(f, b));
                }
            }
        }
        Map<String, Double> od = ammoDamage(orig);
        Map<String, Double> cd = ammoDamage(copy);
        JsonObject dmg = new JsonObject();
        od.forEach((ammo, a) -> {
            Double b = cd.get(ammo);
            if (b != null && Math.abs(a - b) > 1e-9) dmg.addProperty(ammo, b);
        });
        if (dmg.size() > 0) out.add(DAMAGE, dmg);
        return out;
    }

    /** Top-level {@code ammoData.<ammo>.projectile.damage}, in file order. */
    static Map<String, Double> ammoDamage(JsonObject o) {
        Map<String, Double> out = new java.util.LinkedHashMap<>();
        JsonObject data = obj(o, "ammoData");
        if (data == null) return out;
        for (String ammo : data.keySet()) {
            JsonObject proj = projectile(o, ammo);
            Double d = proj == null ? null : num(proj, DAMAGE);
            if (d != null) out.put(ammo, d);
        }
        return out;
    }

    private static JsonObject projectile(JsonObject o, String ammo) {
        JsonObject data = obj(o, "ammoData");
        JsonObject a = data == null ? null : obj(data, ammo);
        return a == null ? null : obj(a, "projectile");
    }

    private static double toEditor(Field f, double raw) {
        return f.ticks() ? PackFiles.ticksToSeconds(raw) : raw;
    }

    private static Field field(String key) {
        for (Field f : FIELDS) if (f.key().equals(key)) return f;
        return null;
    }

    private static <V> Map<ResourceLocation, V> sorted(Map<ResourceLocation, V> m) {
        Map<ResourceLocation, V> t = new TreeMap<>(java.util.Comparator.comparing(ResourceLocation::toString));
        t.putAll(m);
        return t;
    }

    static JsonObject obj(JsonObject o, String key) {
        JsonElement e = o == null ? null : o.get(key);
        return e != null && e.isJsonObject() ? e.getAsJsonObject() : null;
    }

    static Double num(JsonObject o, String key) {
        return number(o == null ? null : o.get(key));
    }

    static Double number(JsonElement e) {
        if (e == null || !e.isJsonPrimitive() || !e.getAsJsonPrimitive().isNumber()) return null;
        double d = e.getAsDouble();
        return Double.isFinite(d) ? d : null;
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? String.valueOf((long) d) : String.valueOf(d);
    }
}
