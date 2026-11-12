package org.gettserver.gettcore.skill;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import net.minecraft.resources.ResourceLocation;

/**
 * bosses.json: per boss entity type aggro behaviour and skill list ({@code bosses: {"gettcore:boss_giraffe": {...}}}).
 * An entity type without an entry uses {@link #DEFAULT}'s entry, else {@link Boss#DEFAULT} (aggro first, no skills).
 */
public record BossConfig(Map<String, Boss> bosses) {
    public static final String FILE = "bosses.json";

    public enum Aggro {
        FIRST, AFTER;

        static final Codec<Aggro> CODEC = Codec.STRING.flatXmap(
                s -> switch (s.toLowerCase(Locale.ROOT)) {
                    case "first" -> DataResult.success(FIRST);
                    case "after" -> DataResult.success(AFTER);
                    default -> DataResult.error(() -> "aggro must be first or after: " + s);
                },
                a -> DataResult.success(a.name().toLowerCase(Locale.ROOT)));
    }

    public record Boss(Aggro aggro, double searchRange, double releaseRange, double reaggroRange,
                       int calmSeconds, Optional<Double> speed, Optional<Double> armor,
                       double preferredDistance, double shoutRange,
                       double enrageBelow, double enrageCooldownMult, List<SkillSpec> skills) {
        /** Every field at its default: aggro on first sight, no skills. */
        public static final Boss DEFAULT = new Boss(Aggro.FIRST, 24, 40, 16, 300, Optional.empty(), Optional.empty(),
                0, 24, 0.3, 0.6, List.of());

        public static final Codec<Boss> CODEC = RecordCodecBuilder.create(i -> i.group(
                SkillSpec.opt(Aggro.CODEC, "aggro", DEFAULT.aggro).forGetter(Boss::aggro),
                SkillSpec.opt(Codec.DOUBLE, "searchRange", DEFAULT.searchRange).forGetter(Boss::searchRange),
                SkillSpec.opt(Codec.DOUBLE, "releaseRange", DEFAULT.releaseRange).forGetter(Boss::releaseRange),
                SkillSpec.opt(Codec.DOUBLE, "reaggroRange", DEFAULT.reaggroRange).forGetter(Boss::reaggroRange),
                SkillSpec.opt(Codec.INT, "calmSeconds", DEFAULT.calmSeconds).forGetter(Boss::calmSeconds),
                Codec.DOUBLE.optionalFieldOf("speed").forGetter(Boss::speed),
                Codec.DOUBLE.optionalFieldOf("armor").forGetter(Boss::armor),
                SkillSpec.opt(Codec.DOUBLE, "preferredDistance", DEFAULT.preferredDistance).forGetter(Boss::preferredDistance),
                SkillSpec.opt(Codec.DOUBLE, "shoutRange", DEFAULT.shoutRange).forGetter(Boss::shoutRange),
                SkillSpec.opt(Codec.DOUBLE, "enrageBelow", DEFAULT.enrageBelow).forGetter(Boss::enrageBelow),
                SkillSpec.opt(Codec.DOUBLE, "enrageCooldownMult", DEFAULT.enrageCooldownMult).forGetter(Boss::enrageCooldownMult),
                SkillSpec.opt(SkillSpec.CODEC.listOf(), "skills", List.<SkillSpec>of()).forGetter(Boss::skills)
        ).apply(i, Boss::new));

        private Boss sanitized(String name, List<String> warnings) {
            List<SkillSpec> kept = SkillSpec.sanitizedList(FILE, name, skills, warnings);
            return new Boss(aggro,
                    SkillSpec.clamp(name, "searchRange", searchRange, 0, 128, warnings),
                    SkillSpec.clamp(name, "releaseRange", releaseRange, 0, 128, warnings),
                    SkillSpec.clamp(name, "reaggroRange", reaggroRange, 0, 128, warnings),
                    (int) SkillSpec.clamp(name, "calmSeconds", calmSeconds, 0, 3600, warnings),
                    speed.map(v -> SkillSpec.clamp(name, "speed", v, 0, 1024, warnings)),
                    armor.map(v -> SkillSpec.clamp(name, "armor", v, 0, 30, warnings)),
                    SkillSpec.clamp(name, "preferredDistance", preferredDistance, 0, 128, warnings),
                    SkillSpec.clamp(name, "shoutRange", shoutRange, 0, 128, warnings),
                    SkillSpec.clamp(name, "enrageBelow", enrageBelow, 0, 1, warnings),
                    SkillSpec.clamp(name, "enrageCooldownMult", enrageCooldownMult, 0.1, 1, warnings),
                    List.copyOf(kept));
        }
    }

    public static final BossConfig DEFAULT;

    static {
        SkillSpec ramgak = new SkillSpec("ramgak", SkillSpec.WIND_LINE, true, true, "람각", 14, 2.5, 0, 32, 1.0, 10,
                new SkillSpec.Shape(30, 3, 0), new SkillSpec.Aftershock(8, 0.5, 4), new SkillSpec.Terrain(true, 0, 2));
        SkillSpec stomp = new SkillSpec("stomp", SkillSpec.STOMP, true, false, "쿠웅", 18, 1.6, 0, 6, 0.8, 6,
                new SkillSpec.Shape(0, 0, 6), new SkillSpec.Aftershock(0, 0, 0), new SkillSpec.Terrain(true, 1, 6));
        Boss d = Boss.DEFAULT;
        Boss giraffe = new Boss(Aggro.AFTER, d.searchRange, d.releaseRange, d.reaggroRange, d.calmSeconds, d.speed,
                d.armor, 0, d.shoutRange, d.enrageBelow, d.enrageCooldownMult, List.of(ramgak, stomp));
        Map<String, Boss> m = new TreeMap<>();
        for (String n : List.of("lion", "tiger", "bear", "elephant", "mammoth", "vulture")) {
            m.put("gettcore:boss_" + n, d);
        }
        m.put("gettcore:boss_giraffe", giraffe);
        DEFAULT = new BossConfig(m);
    }

    public static final Codec<BossConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Boss.CODEC).optionalFieldOf("bosses", Map.of())
                    .forGetter(BossConfig::bosses)
    ).apply(i, BossConfig::new));

    public BossConfig {
        bosses = new TreeMap<>(bosses);
    }

    @Override
    public Map<String, Boss> bosses() {
        return Collections.unmodifiableMap(bosses);
    }

    /** Skips keys that are not entity ids, drops unknown/duplicate skills, clamps numbers (one warning each). */
    public BossConfig sanitized(List<String> warnings) {
        Map<String, Boss> out = new TreeMap<>();
        bosses.forEach((key, b) -> {
            if (ResourceLocation.tryParse(key) == null) {
                warnings.add(FILE + ": " + key + " is not an entity id (skipped)");
                return;
            }
            out.put(key, b.sanitized(key, warnings));
        });
        BossConfig c = new BossConfig(out);
        return c.equals(this) ? this : c;
    }

    /** This config's entry, else {@link #DEFAULT}'s, else {@link Boss#DEFAULT}. */
    public Boss boss(ResourceLocation entityType) {
        String key = entityType.toString();
        Boss b = bosses.get(key);
        if (b != null) return b;
        b = DEFAULT.bosses.get(key);
        return b != null ? b : Boss.DEFAULT;
    }
}
