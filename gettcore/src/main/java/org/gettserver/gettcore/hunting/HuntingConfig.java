package org.gettserver.gettcore.hunting;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import org.gettserver.gettcore.skill.SkillSpec;

/**
 * hunting.json: hunting zones (사냥터). Inside a zone natural spawns are blocked and only the zone's variants appear.
 * Missing fields fall back to defaults; {@link #sanitized(List)} orders min/max, clamps numbers and drops duplicate
 * zone ids and duplicate/blank entry names.
 */
public record HuntingConfig(List<Zone> zones) {
    public static final String FILE = "hunting.json";
    public static final HuntingConfig DEFAULT = new HuntingConfig(List.of());

    public static final int DEFAULT_MAX_ALIVE = 10;
    public static final int MAX_ALIVE_LIMIT = 100;
    public static final int DEFAULT_REFILL_SECONDS = 10;
    public static final int MAX_REFILL_SECONDS = 3600;
    public static final double MIN_HEALTH = 1;
    public static final double MAX_HEALTH = 1024;
    public static final double MAX_ATTACK = 2048;
    public static final double MAX_SPEED = 1024;
    /** Attributes.ARMOR's range. */
    public static final double MAX_ARMOR = 30;
    /** Attributes.SCALE's range. */
    public static final double MIN_SCALE = 0.0625;
    public static final double MAX_SCALE = 16;
    public static final int MAX_WEIGHT = 10000;
    public static final double MAX_XP_MULTIPLIER = 100;

    /** One variant: absolute stats (not multipliers), spawn weight and kill reward. */
    public record Entry(String name, ResourceLocation entity, double health, double attack, double speed, double armor,
                        double scale, int weight, long gold, double xpMultiplier,
                        List<SkillSpec> skills) {
        public static final Codec<Entry> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("name").forGetter(Entry::name),
                ResourceLocation.CODEC.fieldOf("entity").forGetter(Entry::entity),
                Codec.DOUBLE.optionalFieldOf("health", 20.0).forGetter(Entry::health),
                Codec.DOUBLE.optionalFieldOf("attack", 3.0).forGetter(Entry::attack),
                Codec.DOUBLE.optionalFieldOf("speed", 0.23).forGetter(Entry::speed),
                Codec.DOUBLE.optionalFieldOf("armor", 0.0).forGetter(Entry::armor),
                Codec.DOUBLE.optionalFieldOf("scale", 1.0).forGetter(Entry::scale),
                Codec.INT.optionalFieldOf("weight", 1).forGetter(Entry::weight),
                Codec.LONG.optionalFieldOf("gold", 0L).forGetter(Entry::gold),
                Codec.DOUBLE.optionalFieldOf("xpMultiplier", 1.0).forGetter(Entry::xpMultiplier),
                SkillSpec.CODEC.listOf().optionalFieldOf("skills", List.of()).forGetter(Entry::skills)
        ).apply(i, Entry::new));

        public Entry {
            skills = List.copyOf(skills);
        }

        /** A variant without skills. */
        public Entry(String name, ResourceLocation entity, double health, double attack, double speed, double armor,
                     double scale, int weight, long gold, double xpMultiplier) {
            this(name, entity, health, attack, speed, armor, scale, weight, gold, xpMultiplier, List.of());
        }

        Entry sanitized(String where, List<String> warnings) {
            double h = clamp(where, "health", health, MIN_HEALTH, MAX_HEALTH, warnings);
            double a = clamp(where, "attack", attack, 0, MAX_ATTACK, warnings);
            double s = clamp(where, "speed", speed, 0, MAX_SPEED, warnings);
            double ar = clamp(where, "armor", armor, 0, MAX_ARMOR, warnings);
            double sc = clamp(where, "scale", scale, MIN_SCALE, MAX_SCALE, warnings);
            int w = (int) clamp(where, "weight", weight, 0, MAX_WEIGHT, warnings);
            long g = gold;
            if (g < 0) {
                warnings.add(FILE + ": " + where + " gold " + g + " set to 0");
                g = 0;
            }
            double x = clamp(where, "xpMultiplier", xpMultiplier, 0, MAX_XP_MULTIPLIER, warnings);
            Entry e = new Entry(name, entity, h, a, s, ar, sc, w, g, x,
                    SkillSpec.sanitizedList(FILE, where, skills, warnings));
            return e.equals(this) ? this : e;
        }
    }

    /** A box from {@code min} to {@code max} (both inclusive) in {@code dimension} with its variants. */
    public record Zone(String id, ResourceKey<Level> dimension, BlockPos min, BlockPos max, int maxAlive,
                       int refillSeconds, List<Entry> entries) {
        public static final Codec<Zone> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Zone::id),
                ResourceKey.codec(Registries.DIMENSION).fieldOf("dimension").forGetter(Zone::dimension),
                BlockPos.CODEC.fieldOf("min").forGetter(Zone::min),
                BlockPos.CODEC.fieldOf("max").forGetter(Zone::max),
                Codec.INT.optionalFieldOf("maxAlive", DEFAULT_MAX_ALIVE).forGetter(Zone::maxAlive),
                Codec.INT.optionalFieldOf("refillSeconds", DEFAULT_REFILL_SECONDS).forGetter(Zone::refillSeconds),
                Entry.CODEC.listOf().optionalFieldOf("entries", List.of()).forGetter(Zone::entries)
        ).apply(i, Zone::new));

        public Zone {
            entries = List.copyOf(entries);
        }

        public Optional<Entry> entry(String name) {
            return entries.stream().filter(e -> e.name().equals(name)).findFirst();
        }

        /** The block at the middle of the box (rounded down). */
        public BlockPos center() {
            return new BlockPos(Math.floorDiv(min.getX() + max.getX(), 2), Math.floorDiv(min.getY() + max.getY(), 2),
                    Math.floorDiv(min.getZ() + max.getZ(), 2));
        }

        Zone sanitized(List<String> warnings) {
            String where = "zone " + id;
            BlockPos lo = new BlockPos(Math.min(min.getX(), max.getX()), Math.min(min.getY(), max.getY()),
                    Math.min(min.getZ(), max.getZ()));
            BlockPos hi = new BlockPos(Math.max(min.getX(), max.getX()), Math.max(min.getY(), max.getY()),
                    Math.max(min.getZ(), max.getZ()));
            if (!lo.equals(min) || !hi.equals(max)) {
                warnings.add(FILE + ": " + where + " min/max swapped to " + lo.toShortString() + " ~ "
                        + hi.toShortString());
            }
            int alive = (int) clamp(where, "maxAlive", maxAlive, 0, MAX_ALIVE_LIMIT, warnings);
            int refill = (int) clamp(where, "refillSeconds", refillSeconds, 1, MAX_REFILL_SECONDS, warnings);
            List<Entry> out = new ArrayList<>();
            Set<String> names = new HashSet<>();
            for (Entry e : entries) {
                if (e.name().isBlank()) {
                    warnings.add(FILE + ": " + where + " entry with an empty name (skipped)");
                    continue;
                }
                if (!names.add(e.name())) {
                    warnings.add(FILE + ": " + where + " duplicate entry name " + e.name() + " (skipped)");
                    continue;
                }
                out.add(e.sanitized(where + " entry " + e.name(), warnings));
            }
            Zone z = new Zone(id, dimension, lo, hi, alive, refill, out);
            return z.equals(this) ? this : z;
        }
    }

    public static final Codec<HuntingConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Zone.CODEC.listOf().optionalFieldOf("zones", List.of()).forGetter(HuntingConfig::zones)
    ).apply(i, HuntingConfig::new));

    public HuntingConfig {
        zones = List.copyOf(zones);
    }

    /** Fixes every out-of-range value (one warning each, prefixed {@code hunting.json: }); unchanged = same object. */
    public HuntingConfig sanitized(List<String> warnings) {
        List<Zone> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Zone z : zones) {
            if (!ids.add(z.id())) {
                warnings.add(FILE + ": duplicate zone id " + z.id() + " (skipped)");
                continue;
            }
            out.add(z.sanitized(warnings));
        }
        HuntingConfig c = new HuntingConfig(out);
        return c.equals(this) ? this : c;
    }

    public Optional<Zone> zone(String id) {
        return zones.stream().filter(z -> z.id().equals(id)).findFirst();
    }

    /** This config with {@code zone} appended. */
    public HuntingConfig withZone(Zone zone) {
        List<Zone> next = new ArrayList<>(zones);
        next.add(zone);
        return new HuntingConfig(next);
    }

    /** This config without the zone {@code id}. */
    public HuntingConfig without(String id) {
        return new HuntingConfig(zones.stream().filter(z -> !z.id().equals(id)).toList());
    }

    private static double clamp(String where, String field, double v, double min, double max, List<String> warnings) {
        if (Double.isNaN(v)) {
            warnings.add(FILE + ": " + where + " " + field + " is not a number, set to " + min);
            return min;
        }
        if (v < min || v > max) {
            double c = Math.max(min, Math.min(max, v));
            warnings.add(FILE + ": " + where + " " + field + " " + v + " clamped to " + c);
            return c;
        }
        return v;
    }
}
