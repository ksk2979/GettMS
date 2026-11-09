package org.gettserver.gettcore.mobs;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * mobs.json: per entity type base stat overrides ({@code overrides: {"minecraft:zombie": {"health": 40}}}). A missing
 * value keeps the type's own default. Applied to newly appearing mobs only (see {@link MobStatsRules}).
 */
public record MobStatsConfig(Map<String, Stats> overrides) {
    public static final String FILE = "mobs.json";
    public static final MobStatsConfig DEFAULT = new MobStatsConfig(Map.of());
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
                    .forGetter(MobStatsConfig::overrides)
    ).apply(i, MobStatsConfig::new));

    public MobStatsConfig {
        overrides = new TreeMap<>(overrides);
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
        MobStatsConfig c = new MobStatsConfig(out);
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
