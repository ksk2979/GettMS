package org.gettserver.gettcore.fieldgold;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * field_gold.json: gold for killing monsters that appeared in the field (not hunting zones, spawners, eggs...).
 * Price = health x healthRate + attack x attackRate + armor x armorRate, rounded and kept within [min, max];
 * {@code overrides} set a fixed price per entity type (0 = this mob pays nothing).
 */
public record FieldGoldConfig(boolean enabled, long min, long max, double healthRate, double attackRate,
                              double armorRate, Map<String, Long> overrides) {
    public static final String FILE = "field_gold.json";
    public static final long MAX_GOLD = 1_000_000;
    public static final double MAX_RATE = 1000;

    public static final FieldGoldConfig DEFAULT = new FieldGoldConfig(true, 10, 1000, 0.5, 1.0, 1.0,
            Map.of("minecraft:enderman", 40L, "wom:hollow", 80L));

    public static final Codec<FieldGoldConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.BOOL.optionalFieldOf("enabled", DEFAULT.enabled).forGetter(FieldGoldConfig::enabled),
            Codec.LONG.optionalFieldOf("min", DEFAULT.min).forGetter(FieldGoldConfig::min),
            Codec.LONG.optionalFieldOf("max", DEFAULT.max).forGetter(FieldGoldConfig::max),
            Codec.DOUBLE.optionalFieldOf("healthRate", DEFAULT.healthRate).forGetter(FieldGoldConfig::healthRate),
            Codec.DOUBLE.optionalFieldOf("attackRate", DEFAULT.attackRate).forGetter(FieldGoldConfig::attackRate),
            Codec.DOUBLE.optionalFieldOf("armorRate", DEFAULT.armorRate).forGetter(FieldGoldConfig::armorRate),
            Codec.unboundedMap(Codec.STRING, Codec.LONG).optionalFieldOf("overrides", Map.of())
                    .forGetter(FieldGoldConfig::overrides)
    ).apply(i, FieldGoldConfig::new));

    public FieldGoldConfig {
        overrides = Collections.unmodifiableMap(new TreeMap<>(overrides));
    }

    /** Clamps numbers into range and drops override keys that are not entity ids (one warning each). */
    public FieldGoldConfig sanitized(List<String> warnings) {
        long mn = clampLong("min", min, 0, MAX_GOLD, warnings);
        long mx = clampLong("max", max, 0, MAX_GOLD, warnings);
        if (mx < mn) {
            warnings.add(FILE + ": max " + mx + " is below min " + mn + " (max set to " + mn + ")");
            mx = mn;
        }
        double hr = clampRate("healthRate", healthRate, warnings);
        double ar = clampRate("attackRate", attackRate, warnings);
        double rr = clampRate("armorRate", armorRate, warnings);
        Map<String, Long> out = new TreeMap<>();
        overrides.forEach((key, gold) -> {
            if (ResourceLocation.tryParse(key) == null) {
                warnings.add(FILE + ": " + key + " is not an entity id (skipped)");
                return;
            }
            out.put(key, clampLong(key, gold, 0, MAX_GOLD, warnings));
        });
        FieldGoldConfig c = new FieldGoldConfig(enabled, mn, mx, hr, ar, rr, out);
        return c.equals(this) ? this : c;
    }

    /** Gold for one kill of {@code type} with these current stats; 0 = nothing. */
    public long price(ResourceLocation type, double health, double attack, double armor) {
        if (!enabled) return 0;
        Long fixed = overrides.get(type.toString());
        if (fixed != null) return fixed;
        double raw = safe(health) * healthRate + safe(attack) * attackRate + safe(armor) * armorRate;
        return Math.max(min, Math.min(max, Math.round(raw)));
    }

    private static double safe(double v) {
        return Double.isFinite(v) && v > 0 ? v : 0;
    }

    private static long clampLong(String field, long v, long lo, long hi, List<String> warnings) {
        if (v >= lo && v <= hi) return v;
        long c = Math.max(lo, Math.min(hi, v));
        warnings.add(FILE + ": " + field + " " + v + " clamped to " + c);
        return c;
    }

    private static double clampRate(String field, double v, List<String> warnings) {
        if (Double.isFinite(v) && v >= 0 && v <= MAX_RATE) return v;
        double c = Double.isNaN(v) ? 0 : Math.max(0, Math.min(MAX_RATE, v));
        warnings.add(FILE + ": " + field + " " + v + " clamped to " + c);
        return c;
    }
}
