package org.gettserver.gettcore.skill;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import java.util.Optional;

/** One boss skill from bosses.json. Which of {@code shape}/{@code aftershock}/{@code terrain} matter depends on {@code type}. */
public record SkillSpec(String id, String type, boolean enabled, boolean shout, String shoutText,
                        double damage, double knockback, double minRange, double maxRange,
                        double chargeSeconds, double cooldownSeconds,
                        Shape shape, Aftershock aftershock, Terrain terrain) {
    public static final String WIND_LINE = "wind_line";
    public static final String STOMP = "stomp";
    public static final int MAX_SHOUT_TEXT = 24;

    /** {@code length}/{@code width} for a line, {@code radius} for a circle. */
    public record Shape(double length, double width, double radius) {
        public static final Codec<Shape> CODEC = RecordCodecBuilder.create(i -> i.group(
                opt(Codec.DOUBLE, "length", 0.0).forGetter(Shape::length),
                opt(Codec.DOUBLE, "width", 0.0).forGetter(Shape::width),
                opt(Codec.DOUBLE, "radius", 0.0).forGetter(Shape::radius)
        ).apply(i, Shape::new));
    }

    public record Aftershock(double damage, double delaySeconds, double width) {
        public static final Codec<Aftershock> CODEC = RecordCodecBuilder.create(i -> i.group(
                opt(Codec.DOUBLE, "damage", 0.0).forGetter(Aftershock::damage),
                opt(Codec.DOUBLE, "delaySeconds", 0.0).forGetter(Aftershock::delaySeconds),
                opt(Codec.DOUBLE, "width", 0.0).forGetter(Aftershock::width)
        ).apply(i, Aftershock::new));
    }

    /** {@code width} = trench / crater diameter. */
    public record Terrain(boolean enabled, int depth, int width) {
        public static final Codec<Terrain> CODEC = RecordCodecBuilder.create(i -> i.group(
                opt(Codec.BOOL, "enabled", false).forGetter(Terrain::enabled),
                opt(Codec.INT, "depth", 0).forGetter(Terrain::depth),
                opt(Codec.INT, "width", 0).forGetter(Terrain::width)
        ).apply(i, Terrain::new));
    }

    public static final Codec<SkillSpec> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(SkillSpec::id),
            Codec.STRING.fieldOf("type").forGetter(SkillSpec::type),
            opt(Codec.BOOL, "enabled", true).forGetter(SkillSpec::enabled),
            opt(Codec.BOOL, "shout", false).forGetter(SkillSpec::shout),
            opt(Codec.STRING, "shoutText", "").forGetter(SkillSpec::shoutText),
            opt(Codec.DOUBLE, "damage", 10.0).forGetter(SkillSpec::damage),
            opt(Codec.DOUBLE, "knockback", 1.0).forGetter(SkillSpec::knockback),
            opt(Codec.DOUBLE, "minRange", 0.0).forGetter(SkillSpec::minRange),
            opt(Codec.DOUBLE, "maxRange", 24.0).forGetter(SkillSpec::maxRange),
            opt(Codec.DOUBLE, "chargeSeconds", 1.0).forGetter(SkillSpec::chargeSeconds),
            opt(Codec.DOUBLE, "cooldownSeconds", 10.0).forGetter(SkillSpec::cooldownSeconds),
            opt(Shape.CODEC, "shape", new Shape(0, 0, 0)).forGetter(SkillSpec::shape),
            opt(Aftershock.CODEC, "aftershock", new Aftershock(0, 0, 0)).forGetter(SkillSpec::aftershock),
            opt(Terrain.CODEC, "terrain", new Terrain(false, 0, 0)).forGetter(SkillSpec::terrain)
    ).apply(i, SkillSpec::new));

    /** Optional field that is always written back out (an operator sees every knob in the example file). */
    static <T> MapCodec<T> opt(Codec<T> codec, String name, T def) {
        return codec.optionalFieldOf(name).xmap(o -> o.orElse(def), Optional::of);
    }

    public boolean knownType() {
        return WIND_LINE.equals(type) || STOMP.equals(type);
    }

    public int chargeTicks() {
        return (int) Math.round(chargeSeconds * 20);
    }

    public int cooldownTicks() {
        return (int) Math.round(cooldownSeconds * 20);
    }

    /** Clamps every number into its allowed range (one warning each). {@code where} names the skill in warnings. */
    public SkillSpec sanitized(String where, List<String> warnings) {
        String text = shoutText.length() > MAX_SHOUT_TEXT ? shoutText.substring(0, MAX_SHOUT_TEXT) : shoutText;
        if (!text.equals(shoutText)) {
            warnings.add(BossConfig.FILE + ": " + where + " shoutText cut to " + MAX_SHOUT_TEXT + " chars");
        }
        // Only the shape fields the skill type uses are range-checked (a stomp has no length, a line no radius).
        boolean line = WIND_LINE.equals(type);
        boolean circle = STOMP.equals(type);
        Shape sh = new Shape(
                line ? clamp(where, "shape.length", shape.length, 1, 64, warnings) : shape.length,
                line ? clamp(where, "shape.width", shape.width, 0.5, 32, warnings) : shape.width,
                circle ? clamp(where, "shape.radius", shape.radius, 0.5, 32, warnings) : shape.radius);
        Aftershock af = new Aftershock(
                clamp(where, "aftershock.damage", aftershock.damage, 0, 2048, warnings),
                clamp(where, "aftershock.delaySeconds", aftershock.delaySeconds, 0, 10, warnings),
                clamp(where, "aftershock.width", aftershock.width, 0, 32, warnings));
        Terrain te = new Terrain(terrain.enabled,
                (int) clamp(where, "terrain.depth", terrain.depth, 0, 16, warnings),
                (int) clamp(where, "terrain.width", terrain.width, 0, 32, warnings));
        SkillSpec out = new SkillSpec(id, type, enabled, shout, text,
                clamp(where, "damage", damage, 0, 2048, warnings),
                clamp(where, "knockback", knockback, 0, 10, warnings),
                clamp(where, "minRange", minRange, 0, 64, warnings),
                clamp(where, "maxRange", maxRange, 0, 64, warnings),
                clamp(where, "chargeSeconds", chargeSeconds, 0.2, 10, warnings),
                clamp(where, "cooldownSeconds", cooldownSeconds, 0.5, 600, warnings),
                sh, af, te);
        return out.equals(this) ? this : out;
    }

    /**
     * The usable skills of one owner: unknown types and duplicate ids dropped, the rest clamped (one warning each,
     * prefixed {@code file: } and naming {@code owner}).
     */
    public static List<SkillSpec> sanitizedList(String file, String owner, List<SkillSpec> skills, List<String> warnings) {
        List<SkillSpec> kept = new java.util.ArrayList<>();
        java.util.Set<String> ids = new java.util.HashSet<>();
        String own = BossConfig.FILE + ": ";
        for (SkillSpec s : skills) {
            if (!s.knownType()) {
                warnings.add(file + ": " + owner + " skill " + s.id() + ": unknown type " + s.type() + " (skipped)");
            } else if (!ids.add(s.id())) {
                warnings.add(file + ": " + owner + " skill " + s.id() + ": duplicate id (skipped)");
            } else {
                List<String> w = new java.util.ArrayList<>();
                kept.add(s.sanitized(owner + " skill " + s.id(), w));
                for (String m : w) warnings.add(m.startsWith(own) ? file + ": " + m.substring(own.length()) : m);
            }
        }
        return kept;
    }

    static double clamp(String where, String field, double v, double min, double max, List<String> warnings) {
        if (!Double.isNaN(v) && v >= min && v <= max) return v;
        double c = Double.isNaN(v) ? min : Math.max(min, Math.min(max, v));
        warnings.add(BossConfig.FILE + ": " + where + " " + field + " " + v + " clamped to " + c);
        return c;
    }
}
