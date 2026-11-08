package org.gettserver.gettcore.pet;

import com.google.gson.JsonParser;
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
     * {@code minPercent}/{@code maxPercent}: health range (percent of max health) in which capture is allowed.
     * Chances and {@code obtainPercent} are percents (0-100).
     */
    public record Capture(double minPercent, double maxPercent, Map<CaptureTier, Double> ballChance,
                          Map<CaptureTier, Double> bossBallChance, double obtainPercent) {
        static final Map<CaptureTier, Double> DEFAULT_BALL = tiers(10, 15, 20, 30);
        static final Map<CaptureTier, Double> DEFAULT_BOSS_BALL = tiers(5, 8, 12, 20);
        static final Capture DEFAULT = new Capture(1, 20, DEFAULT_BALL, DEFAULT_BOSS_BALL, 20);

        private static Map<CaptureTier, Double> tiers(double iron, double gold, double diamond, double netherite) {
            Map<CaptureTier, Double> m = new EnumMap<>(CaptureTier.class);
            m.put(CaptureTier.IRON, iron);
            m.put(CaptureTier.GOLD, gold);
            m.put(CaptureTier.DIAMOND, diamond);
            m.put(CaptureTier.NETHERITE, netherite);
            return m;
        }

        /** Tier map codec; tiers missing from the JSON take the default. */
        private static Codec<Map<CaptureTier, Double>> tierMap(Map<CaptureTier, Double> def) {
            return Codec.unboundedMap(CaptureTier.CODEC, Codec.DOUBLE).xmap(read -> {
                Map<CaptureTier, Double> m = new EnumMap<>(def);
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

    /** Copy with out-of-range numbers replaced/clamped; one line in {@code warnings} per changed field. */
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
        Map<CaptureTier, Double> ball = chances("capture.ballChance", c.ballChance, d.ballChance, warnings);
        Map<CaptureTier, Double> boss = chances("capture.bossBallChance", c.bossBallChance, d.bossBallChance, warnings);
        double obtain = percent("capture.obtainPercent", c.obtainPercent, d.obtainPercent, warnings);

        Map<String, PetSpecies> fixed = new HashMap<>();
        species.forEach((key, s) -> {
            double p = s.obtainPercent();
            if (!(p >= 0 && p <= 100)) {
                double clamped = Double.isNaN(p) ? 0 : Math.max(0, Math.min(100, p));
                warnings.add("pets.json: species " + key + " obtainPercent " + p + " out of [0, 100], using " + clamped);
                s = new PetSpecies(s.id(), s.kind(), s.petEntity(), s.attack(), s.speed(), s.attackSpeed(), s.flySpeed(),
                        s.climbSpeed(), s.boss(), s.sizeScale(), s.healthMult(), s.attackMult(), s.killGold(), clamped);
            }
            fixed.put(key, s);
        });
        return new PetConfig(healthbar, new Capture(min, max, ball, boss, obtain), Map.copyOf(fixed));
    }

    private static double percent(String name, double v, double def, List<String> warnings) {
        if (v >= 0 && v <= 100) return v;
        warnings.add("pets.json: " + name + " " + v + " must be within [0, 100], using default " + def);
        return def;
    }

    private static Map<CaptureTier, Double> chances(String name, Map<CaptureTier, Double> m,
                                                    Map<CaptureTier, Double> def, List<String> warnings) {
        Map<CaptureTier, Double> out = new EnumMap<>(CaptureTier.class);
        for (CaptureTier t : CaptureTier.values()) {
            out.put(t, percent(name + "." + t.key(), m.getOrDefault(t, def.get(t)), def.get(t), warnings));
        }
        return out;
    }
}
