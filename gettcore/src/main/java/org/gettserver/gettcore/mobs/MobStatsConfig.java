package org.gettserver.gettcore.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.drop.Drop;

/**
 * mobs.json: per entity type base stat overrides ({@code overrides: {"minecraft:zombie": {"health": 40}}}). A missing
 * value keeps the type's own default. Applied to newly appearing mobs only (see {@link MobStatsRules}).
 * {@code drops: {"minecraft:zombie": [{"item": ..., "chance": 10, "min": 1, "max": 2}]}}: extra kill drops per
 * entity type (see {@link org.gettserver.gettcore.drop.DropEvents}); these apply to mobs already in the world too.
 */
public record MobStatsConfig(Map<String, Stats> overrides, Map<String, List<Drop>> drops) {
    public static final String FILE = "mobs.json";
    public static final MobStatsConfig DEFAULT = new MobStatsConfig(Map.of(), Map.of());
    public static final double MIN_HEALTH = 1;
    public static final double MAX_HEALTH = 1024;
    public static final double MAX_ATTACK = 2048;
    public static final double MAX_SPEED = 1024;
    public static final double MAX_ARMOR = 30;

    /** Absent = keep the entity type's default. */
    public record Stats(Optional<Double> health, Optional<Double> attack, Optional<Double> speed,
                        Optional<Double> armor) {
        public static final Codec<Stats> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("health").forGetter(Stats::health),
                Codec.DOUBLE.optionalFieldOf("attack").forGetter(Stats::attack),
                Codec.DOUBLE.optionalFieldOf("speed").forGetter(Stats::speed),
                Codec.DOUBLE.optionalFieldOf("armor").forGetter(Stats::armor)
        ).apply(i, Stats::new));

        public boolean isEmpty() {
            return health.isEmpty() && attack.isEmpty() && speed.isEmpty() && armor.isEmpty();
        }
    }

    public static final Codec<MobStatsConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Stats.CODEC).optionalFieldOf("overrides", Map.of())
                    .forGetter(MobStatsConfig::overrides),
            Codec.unboundedMap(Codec.STRING, Drop.LIST_CODEC).optionalFieldOf("drops", Map.of())
                    .forGetter(MobStatsConfig::drops)
    ).apply(i, MobStatsConfig::new));

    public MobStatsConfig {
        overrides = new TreeMap<>(overrides);
        Map<String, List<Drop>> d = new TreeMap<>();
        drops.forEach((k, v) -> d.put(k, List.copyOf(v)));
        drops = java.util.Collections.unmodifiableMap(d);
    }

    /** Stat overrides only, no drops. */
    public MobStatsConfig(Map<String, Stats> overrides) {
        this(overrides, Map.of());
    }

    /** The extra drops of an entity type (empty when none). */
    public List<Drop> dropsOf(ResourceLocation type) {
        return drops.getOrDefault(type.toString(), List.of());
    }

    @Override
    public Map<String, Stats> overrides() {
        return java.util.Collections.unmodifiableMap(overrides);
    }

    /** Drops keys that are not entity ids and rows with no value, clamps values (one warning each). */
    public MobStatsConfig sanitized(List<String> warnings) {
        Map<String, Stats> out = new TreeMap<>();
        overrides.forEach((key, s) -> {
            if (ResourceLocation.tryParse(key) == null) {
                warnings.add(FILE + ": " + key + " is not an entity id (skipped)");
                return;
            }
            if (s.isEmpty()) return;
            out.put(key, new Stats(clamp(key, "health", s.health(), MIN_HEALTH, MAX_HEALTH, warnings),
                    clamp(key, "attack", s.attack(), 0, MAX_ATTACK, warnings),
                    clamp(key, "speed", s.speed(), 0, MAX_SPEED, warnings),
                    clamp(key, "armor", s.armor(), 0, MAX_ARMOR, warnings)));
        });
        Map<String, List<Drop>> dropsOut = new TreeMap<>();
        drops.forEach((key, list) -> {
            if (ResourceLocation.tryParse(key) == null) {
                warnings.add(FILE + ": drops " + key + " is not an entity id (skipped)");
                return;
            }
            if (!list.isEmpty()) dropsOut.put(key, Drop.sanitizedList(FILE + ": " + key, list, warnings));
        });
        MobStatsConfig c = new MobStatsConfig(out, dropsOut);
        return c.equals(this) ? this : c;
    }

    public Optional<Stats> get(ResourceLocation type) {
        return Optional.ofNullable(overrides.get(type.toString()));
    }

    private static Optional<Double> clamp(String key, String field, Optional<Double> v, double min, double max,
                                          List<String> warnings) {
        if (v.isEmpty()) return v;
        double d = v.get();
        if (Double.isNaN(d) || d < min || d > max) {
            double c = Double.isNaN(d) ? min : Math.max(min, Math.min(max, d));
            warnings.add(FILE + ": " + key + " " + field + " " + d + " clamped to " + c);
            return Optional.of(c);
        }
        return v;
    }
}
