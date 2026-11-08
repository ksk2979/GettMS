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
     * Chances and {@code obtainPercent} are percents (0-100).
     */
    public record Capture(double minPercent, double maxPercent, Map<CaptureTier, ChanceRange> ballChance,
                          Map<CaptureTier, ChanceRange> bossBallChance, double obtainPercent) {
        static final Map<CaptureTier, ChanceRange> DEFAULT_BALL = tiers(5, 25, 8, 35, 12, 50, 20, 70);
        static final Map<CaptureTier, ChanceRange> DEFAULT_BOSS_BALL = tiers(3, 12, 5, 18, 8, 25, 12, 40);
        static final Capture DEFAULT = new Capture(1, 20, DEFAULT_BALL, DEFAULT_BOSS_BALL, 20);

        /** Base/max pairs in tier order: iron, gold, diamond, netherite. */
        private static Map<CaptureTier, ChanceRange> tiers(double... baseMax) {
            Map<CaptureTier, ChanceRange> m = new EnumMap<>(CaptureTier.class);
            CaptureTier[] order = {CaptureTier.IRON, CaptureTier.GOLD, CaptureTier.DIAMOND, CaptureTier.NETHERITE};
            for (int k = 0; k < order.length; k++) m.put(order[k], new ChanceRange(baseMax[2 * k], baseMax[2 * k + 1]));
            return m;
        }

        /** Tier map codec; tiers missing from the JSON take the default. */
        private static Codec<Map<CaptureTier, ChanceRange>> tierMap(Map<CaptureTier, ChanceRange> def) {
            return Codec.unboundedMap(CaptureTier.CODEC, ChanceRange.CODEC).xmap(read -> {
                Map<CaptureTier, ChanceRange> m = new EnumMap<>(def);
                m.putAll(read);
                return m;
            }, m -> m);
        }

        static final Codec<Capture> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("minPercent", DEFAULT.minPercent).forGetter(Capture::minPercent),
                Codec.DOUBLE.optionalFieldOf("maxPercent", DEFAULT.maxPercent).forGetter(Capture::maxPercent),
                tierMap(DEFAULT_BALL).optionalFieldOf("ballChance", DEFAULT_BALL).forGetter(Capture::ballChance),
                tierMap(DEFAULT_BOSS_BALL).optionalFieldOf("bossBallChance", DEFAULT_BOSS_BALL)
                        .forGetter(Capture::bossBallChance),
                Codec.DOUBLE.optionalFieldOf("obtainPercent", DEFAULT.obtainPercent).forGetter(Capture::obtainPercent)
        ).apply(i, Capture::new));
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
     * default), killGold &gt;= 0 (else 0), obtainPercent within 0..100 (clamped).
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
        double obtain = percent("capture.obtainPercent", c.obtainPercent, d.obtainPercent, warnings);

        Map<String, PetSpecies> fixed = new HashMap<>();
        species.forEach((key, s) -> fixed.put(key, sanitizedSpecies(key, s, warnings)));
        HealthBar h = healthbar;
        HealthBar bar = new HealthBar(clamp("healthbar.showDistance", h.showDistance, 1, 128, warnings),
                clamp("healthbar.fadeDelayTicks", h.fadeDelayTicks, 0, 1200, warnings),
                clamp("healthbar.fadeTicks", h.fadeTicks, 1, 200, warnings), h.captureColor);
        return new PetConfig(bar.equals(h) ? h : bar, new Capture(min, max, ball, boss, obtain), Map.copyOf(fixed));
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
        double p = s.obtainPercent();
        if (!(p >= 0 && p <= 100)) {
            double clamped = Double.isNaN(p) ? 0 : Math.max(0, Math.min(100, p));
            warnings.add("pets.json: " + at + "obtainPercent " + p + " out of [0, 100], using " + clamped);
            p = clamped;
        }
        PetSpecies out = new PetSpecies(s.id(), s.kind(), s.petEntity(), attack, speed, attackSpeed, flySpeed,
                climbSpeed, s.boss(), sizeScale, healthMult, attackMult, killGold, p);
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
            if (max < base) {
                warnings.add("pets.json: " + at + ".max " + max + " must be >= base " + base + ", using " + base);
                max = base;
            }
            out.put(t, new ChanceRange(base, max));
        }
        return out;
    }
}
