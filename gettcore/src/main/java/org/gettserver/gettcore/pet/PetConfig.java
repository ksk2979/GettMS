package org.gettserver.gettcore.pet;

import com.google.gson.JsonParser;
import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code config/gettcore/pets.json}: capturable species, capture odds and the mob health bar look.
 * Missing fields fall back to defaults; out-of-range values are fixed by {@link #sanitized(List)}.
 */
public record PetConfig(HealthBar healthbar, Capture capture, Map<String, PetSpecies> species) {

    /** {@code captureColor} is ARGB; JSON form is {@code "#RRGGBB"} (or {@code "#AARRGGBB"}). */
    public record HealthBar(int showDistance, int fadeDelayTicks, int fadeTicks, int captureColor) {
        static final HealthBar DEFAULT = new HealthBar(32, 200, 20, 0xFF55FF55);

        private static final Codec<Integer> COLOR_CODEC = Codec.STRING.comapFlatMap(s -> {
            try {
                String hex = s.startsWith("#") ? s.substring(1) : s;
                if (hex.length() != 6 && hex.length() != 8) throw new NumberFormatException();
                int v = (int) Long.parseLong(hex, 16);
                return DataResult.success(hex.length() == 6 ? 0xFF000000 | v : v);
            } catch (NumberFormatException e) {
                return DataResult.error(() -> "Invalid color (expected #RRGGBB): " + s);
            }
        }, c -> (c >>> 24) == 0xFF ? String.format("#%06X", c & 0xFFFFFF) : String.format("#%08X", c));

        static final Codec<HealthBar> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("showDistance", DEFAULT.showDistance).forGetter(HealthBar::showDistance),
                Codec.INT.optionalFieldOf("fadeDelayTicks", DEFAULT.fadeDelayTicks).forGetter(HealthBar::fadeDelayTicks),
                Codec.INT.optionalFieldOf("fadeTicks", DEFAULT.fadeTicks).forGetter(HealthBar::fadeTicks),
                COLOR_CODEC.optionalFieldOf("captureColor", DEFAULT.captureColor).forGetter(HealthBar::captureColor)
        ).apply(i, HealthBar::new));
    }

    /**
     * Ball capture chance in percent: {@code base} at the top of the capture health range ({@code maxPercent}),
     * rising linearly to {@code max} at its bottom ({@code minPercent}). JSON: {@code {"base": 5, "max": 25}} or a
     * plain number (legacy; base = max, a flat chance).
     */
    public record ChanceRange(double base, double max) {
        private static final Codec<ChanceRange> OBJECT_CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("base").forGetter(ChanceRange::base),
                Codec.DOUBLE.optionalFieldOf("max").forGetter(r -> Optional.of(r.max))
        ).apply(i, (base, max) -> new ChanceRange(base, max.orElse(base))));

        public static final Codec<ChanceRange> CODEC = Codec.either(Codec.DOUBLE, OBJECT_CODEC).xmap(
                e -> e.map(d -> new ChanceRange(d, d), r -> r), Either::right);
    }

    /**
     * {@code minPercent}/{@code maxPercent}: health range (percent of max health) in which capture is allowed.
     * Chances are percents (0-100). {@code obtainChance}/{@code bossObtainChance}: second-stage (obtain) percent per
     * capture ball tier. {@code throwCooldownTicks}: item cooldown applied to every capture ball after a throw
     * (0..200). The legacy single {@code obtainPercent} is still read: an obtain table missing from the JSON takes
     * that value for all four tiers. It is never written back.
     */
    public record Capture(double minPercent, double maxPercent, Map<CaptureTier, ChanceRange> ballChance,
                          Map<CaptureTier, ChanceRange> bossBallChance, Map<CaptureTier, Double> obtainChance,
                          Map<CaptureTier, Double> bossObtainChance, int throwCooldownTicks) {
        private static final CaptureTier[] ORDER =
                {CaptureTier.IRON, CaptureTier.GOLD, CaptureTier.DIAMOND, CaptureTier.NETHERITE};

        static final Map<CaptureTier, ChanceRange> DEFAULT_BALL = tiers(5, 25, 8, 35, 12, 50, 20, 70);
        static final Map<CaptureTier, ChanceRange> DEFAULT_BOSS_BALL = tiers(3, 12, 5, 18, 8, 25, 12, 40);
        static final Map<CaptureTier, Double> DEFAULT_OBTAIN = percents(20, 25, 30, 40);
        static final Map<CaptureTier, Double> DEFAULT_BOSS_OBTAIN = percents(15, 20, 25, 35);
        static final Capture DEFAULT = new Capture(1, 20, DEFAULT_BALL, DEFAULT_BOSS_BALL, DEFAULT_OBTAIN,
                DEFAULT_BOSS_OBTAIN, 20);

        /** Base/max pairs in tier order: iron, gold, diamond, netherite. */
        private static Map<CaptureTier, ChanceRange> tiers(double... baseMax) {
            Map<CaptureTier, ChanceRange> m = new EnumMap<>(CaptureTier.class);
            for (int k = 0; k < ORDER.length; k++) m.put(ORDER[k], new ChanceRange(baseMax[2 * k], baseMax[2 * k + 1]));
            return m;
        }

        /** Percents in tier order: iron, gold, diamond, netherite. */
        private static Map<CaptureTier, Double> percents(double... values) {
            Map<CaptureTier, Double> m = new EnumMap<>(CaptureTier.class);
            for (int k = 0; k < ORDER.length; k++) m.put(ORDER[k], values[k]);
            return m;
        }

        /** The same percent for every tier (legacy single {@code obtainPercent}). */
        static Map<CaptureTier, Double> allTiers(double percent) {
            return percents(percent, percent, percent, percent);
        }

        /** Tier map codec; tiers missing from the JSON take the default. */
        private static <V> Codec<Map<CaptureTier, V>> tierMap(Codec<V> value, Map<CaptureTier, V> def) {
            return Codec.unboundedMap(CaptureTier.CODEC, value).xmap(read -> {
                Map<CaptureTier, V> m = new EnumMap<>(def);
                m.putAll(read);
                return m;
            }, m -> m);
        }

        /** The obtain table from the JSON, else the legacy single percent on all tiers, else the default. */
        private static Map<CaptureTier, Double> obtainTable(Optional<Map<CaptureTier, Double>> table,
                                                            Optional<Double> legacy, Map<CaptureTier, Double> def) {
            return table.orElseGet(() -> legacy.map(Capture::allTiers).orElse(def));
        }

        static final Codec<Capture> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("minPercent", DEFAULT.minPercent).forGetter(Capture::minPercent),
                Codec.DOUBLE.optionalFieldOf("maxPercent", DEFAULT.maxPercent).forGetter(Capture::maxPercent),
                tierMap(ChanceRange.CODEC, DEFAULT_BALL).optionalFieldOf("ballChance", DEFAULT_BALL)
                        .forGetter(Capture::ballChance),
                tierMap(ChanceRange.CODEC, DEFAULT_BOSS_BALL).optionalFieldOf("bossBallChance", DEFAULT_BOSS_BALL)
                        .forGetter(Capture::bossBallChance),
                tierMap(Codec.DOUBLE, DEFAULT_OBTAIN).optionalFieldOf("obtainChance")
                        .forGetter(c -> Optional.of(c.obtainChance)),
                tierMap(Codec.DOUBLE, DEFAULT_BOSS_OBTAIN).optionalFieldOf("bossObtainChance")
                        .forGetter(c -> Optional.of(c.bossObtainChance)),
                // Legacy, read only: never encoded.
                Codec.DOUBLE.optionalFieldOf("obtainPercent").forGetter(c -> Optional.empty()),
                Codec.INT.optionalFieldOf("throwCooldownTicks", DEFAULT.throwCooldownTicks)
                        .forGetter(Capture::throwCooldownTicks)
        ).apply(i, (min, max, ball, bossBall, obtain, bossObtain, legacy, cooldown) -> new Capture(min, max, ball,
                bossBall, obtainTable(obtain, legacy, DEFAULT_OBTAIN),
                obtainTable(bossObtain, legacy, DEFAULT_BOSS_OBTAIN), cooldown)));
    }

    public static final PetConfig DEFAULT = new PetConfig(HealthBar.DEFAULT, Capture.DEFAULT, Map.of());

    /** Species JSON object keyed by wild entity id; each value's {@code id} is set from its key. */
    private static final Codec<Map<String, PetSpecies>> SPECIES_CODEC =
            Codec.unboundedMap(Codec.STRING, PetSpecies.CODEC).xmap(m -> {
                Map<String, PetSpecies> out = new HashMap<>();
                m.forEach((k, v) -> out.put(k, v.withId(k)));
                return Map.copyOf(out);
            }, m -> m);

    public static final Codec<PetConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            HealthBar.CODEC.optionalFieldOf("healthbar", HealthBar.DEFAULT).forGetter(PetConfig::healthbar),
            Capture.CODEC.optionalFieldOf("capture", Capture.DEFAULT).forGetter(PetConfig::capture),
            SPECIES_CODEC.optionalFieldOf("species", Map.of()).forGetter(PetConfig::species)
    ).apply(i, PetConfig::new));

    /** Sent over the wire as the JSON text (the config is small; the client re-parses it). */
    public static final StreamCodec<ByteBuf, PetConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(
            s -> CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new),
            c -> CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString());

    /** Looks a species up by the wild entity type id ({@code namespace:path}). */
    public Optional<PetSpecies> species(ResourceLocation wildEntityType) {
        return Optional.ofNullable(species.get(wildEntityType.toString()));
    }

    /**
     * Copy with out-of-range numbers replaced/clamped; one line in {@code warnings} per changed field. Health bar:
     * showDistance 1..128, fadeDelayTicks 0..1200, fadeTicks 1..200 (clamped). Species: sizeScale, healthMult and
     * attackMult must be &gt; 0 and attack, speed, attackSpeed, flySpeed, climbSpeed &gt;= 0 (else the JSON
     * default), killGold &gt;= 0 (else 0), jumpHeight within 0..10 (else the JSON default); a ballChance or
     * obtainChance override entry outside 0..100 is removed (the global table applies), an override range with
     * max &lt; base gets max = base. Capture: each obtainChance/bossObtainChance tier outside 0..100 takes that
     * tier's default; throwCooldownTicks outside 0..200 takes the default.
     */
    public PetConfig sanitized(List<String> warnings) {
        Capture c = capture;
        Capture d = Capture.DEFAULT;
        double min = percent("capture.minPercent", c.minPercent, d.minPercent, warnings);
        double max = c.maxPercent;
        if (!(max >= min && max <= 100)) {
            double fallback = Math.max(d.maxPercent, min);
            warnings.add("pets.json: capture.maxPercent " + max + " must be within [" + min + ", 100], using " + fallback);
            max = fallback;
        }
        Map<CaptureTier, ChanceRange> ball = chances("capture.ballChance", c.ballChance, d.ballChance, warnings);
        Map<CaptureTier, ChanceRange> boss = chances("capture.bossBallChance", c.bossBallChance, d.bossBallChance, warnings);
        Map<CaptureTier, Double> obtain = percents("capture.obtainChance", c.obtainChance, d.obtainChance, warnings);
        Map<CaptureTier, Double> bossObtain = percents("capture.bossObtainChance", c.bossObtainChance,
                d.bossObtainChance, warnings);
        int cooldown = c.throwCooldownTicks;
        if (cooldown < 0 || cooldown > 200) {
            warnings.add("pets.json: capture.throwCooldownTicks " + cooldown + " must be within [0, 200], using default "
                    + d.throwCooldownTicks);
            cooldown = d.throwCooldownTicks;
        }

        Map<String, PetSpecies> fixed = new HashMap<>();
        species.forEach((key, s) -> fixed.put(key, sanitizedSpecies(key, s, warnings)));
        HealthBar h = healthbar;
        HealthBar bar = new HealthBar(clamp("healthbar.showDistance", h.showDistance, 1, 128, warnings),
                clamp("healthbar.fadeDelayTicks", h.fadeDelayTicks, 0, 1200, warnings),
                clamp("healthbar.fadeTicks", h.fadeTicks, 1, 200, warnings), h.captureColor);
        return new PetConfig(bar.equals(h) ? h : bar,
                new Capture(min, max, ball, boss, obtain, bossObtain, cooldown), Map.copyOf(fixed));
    }

    private static PetSpecies sanitizedSpecies(String key, PetSpecies s, List<String> warnings) {
        String at = "species " + key + " ";
        double attack = atLeastZero(at + "attack", s.attack(), PetSpecies.DEFAULT_ATTACK, warnings);
        double speed = atLeastZero(at + "speed", s.speed(), PetSpecies.DEFAULT_SPEED, warnings);
        double attackSpeed = atLeastZero(at + "attackSpeed", s.attackSpeed(), PetSpecies.DEFAULT_ATTACK_SPEED,
                warnings);
        double flySpeed = atLeastZero(at + "flySpeed", s.flySpeed(), PetSpecies.DEFAULT_FLY_SPEED, warnings);
        double climbSpeed = atLeastZero(at + "climbSpeed", s.climbSpeed(), PetSpecies.DEFAULT_CLIMB_SPEED, warnings);
        double sizeScale = aboveZero(at + "sizeScale", s.sizeScale(), warnings);
        double healthMult = aboveZero(at + "healthMult", s.healthMult(), warnings);
        double attackMult = aboveZero(at + "attackMult", s.attackMult(), warnings);
        long killGold = s.killGold();
        if (killGold < 0) {
            warnings.add("pets.json: " + at + "killGold " + killGold + " must be >= 0, using 0");
            killGold = 0;
        }
        double jump = s.jumpHeight();
        if (!(jump >= 0 && jump <= PetSpecies.MAX_JUMP_HEIGHT)) {
            warnings.add("pets.json: " + at + "jumpHeight " + jump + " must be within [0, "
                    + PetSpecies.MAX_JUMP_HEIGHT + "], using default " + PetSpecies.DEFAULT_JUMP_HEIGHT);
            jump = PetSpecies.DEFAULT_JUMP_HEIGHT;
        }
        Map<CaptureTier, ChanceRange> ball = new EnumMap<>(CaptureTier.class);
        s.ballChance().forEach((t, r) -> {
            ChanceRange fixed = overrideRange(at + "ballChance." + t.key(), r, warnings);
            if (fixed != null) ball.put(t, fixed);
        });
        Map<CaptureTier, Double> obtain = new EnumMap<>(CaptureTier.class);
        s.obtainChance().forEach((t, p) -> {
            if (p >= 0 && p <= 100) obtain.put(t, p);
            else warnings.add("pets.json: " + at + "obtainChance." + t.key() + " " + p
                    + " must be within [0, 100], removed (global value used)");
        });
        PetSpecies out = new PetSpecies(s.id(), s.kind(), s.petEntity(), attack, speed, attackSpeed, flySpeed,
                climbSpeed, s.boss(), sizeScale, healthMult, attackMult, killGold, jump, ball, obtain);
        return out.equals(s) ? s : out;
    }

    private static double atLeastZero(String name, double v, double def, List<String> warnings) {
        if (v >= 0) return v;
        warnings.add("pets.json: " + name + " " + v + " must be >= 0, using default " + def);
        return def;
    }

    private static double aboveZero(String name, double v, List<String> warnings) {
        if (v > 0) return v;
        warnings.add("pets.json: " + name + " " + v + " must be > 0, using default " + PetSpecies.DEFAULT_MULT);
        return PetSpecies.DEFAULT_MULT;
    }

    private static int clamp(String name, int v, int min, int max, List<String> warnings) {
        if (v >= min && v <= max) return v;
        int c = Math.max(min, Math.min(max, v));
        warnings.add("pets.json: " + name + " " + v + " must be within [" + min + ", " + max + "], using " + c);
        return c;
    }

    private static double percent(String name, double v, double def, List<String> warnings) {
        if (v >= 0 && v <= 100) return v;
        warnings.add("pets.json: " + name + " " + v + " must be within [0, 100], using default " + def);
        return def;
    }

    /** Each tier within 0..100, else that tier's default. */
    private static Map<CaptureTier, Double> percents(String name, Map<CaptureTier, Double> m,
                                                     Map<CaptureTier, Double> def, List<String> warnings) {
        Map<CaptureTier, Double> out = new EnumMap<>(CaptureTier.class);
        for (CaptureTier t : CaptureTier.values()) {
            double d = def.get(t);
            out.put(t, percent(name + "." + t.key(), m.getOrDefault(t, d), d, warnings));
        }
        return out;
    }

    /** Base and max each within 0..100 (else that tier's default), then max &gt;= base (else max = base). */
    private static Map<CaptureTier, ChanceRange> chances(String name, Map<CaptureTier, ChanceRange> m,
                                                         Map<CaptureTier, ChanceRange> def, List<String> warnings) {
        Map<CaptureTier, ChanceRange> out = new EnumMap<>(CaptureTier.class);
        for (CaptureTier t : CaptureTier.values()) {
            ChanceRange d = def.get(t);
            ChanceRange r = m.getOrDefault(t, d);
            String at = name + "." + t.key();
            double base = percent(at + ".base", r.base(), d.base(), warnings);
            double max = percent(at + ".max", r.max(), d.max(), warnings);
            out.put(t, maxAtLeastBase(at, base, max, warnings));
        }
        return out;
    }

    /** {@code max < base} becomes {@code max = base}, with a warning. */
    private static ChanceRange maxAtLeastBase(String at, double base, double max, List<String> warnings) {
        if (max >= base) return new ChanceRange(base, max);
        warnings.add("pets.json: " + at + ".max " + max + " must be >= base " + base + ", using " + base);
        return new ChanceRange(base, base);
    }

    /** A species override range; null (removed, with a warning) if base or max is outside 0..100. */
    private static ChanceRange overrideRange(String at, ChanceRange r, List<String> warnings) {
        if (!(r.base() >= 0 && r.base() <= 100 && r.max() >= 0 && r.max() <= 100)) {
            warnings.add("pets.json: " + at + " " + r.base() + "~" + r.max()
                    + " must be within [0, 100], removed (global value used)");
            return null;
        }
        return maxAtLeastBase(at, r.base(), r.max(), warnings);
    }
}
