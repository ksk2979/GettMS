package org.gettserver.gettcore.equipment;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * equipment.json: per item attribute overrides as the <b>tooltip's final value</b>
 * ({@code items: {"minecraft:diamond_sword": {"attackDamage": 9, "attackSpeed": 1.2}}}). A missing value keeps the
 * item's own modifier. knockbackResistance is the raw attribute value 0..1 (the tooltip shows it as a percent).
 * Applied by {@link EquipmentEffects} through {@link EquipmentRules}.
 */
public record EquipmentConfig(Map<String, Values> items) {
    public static final String FILE = "equipment.json";
    public static final EquipmentConfig DEFAULT = new EquipmentConfig(Map.of());
    public static final double MAX_ATTACK_DAMAGE = 2048;
    public static final double MAX_ATTACK_SPEED = 1024;
    public static final double MAX_ARMOR = 30;
    public static final double MAX_TOUGHNESS = 20;
    public static final double MAX_KNOCKBACK_RESISTANCE = 1;

    /** Absent = keep the item's own value. */
    public record Values(Optional<Double> attackDamage, Optional<Double> attackSpeed, Optional<Double> armor,
                         Optional<Double> toughness, Optional<Double> knockbackResistance) {
        public static final Codec<Values> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("attackDamage").forGetter(Values::attackDamage),
                Codec.DOUBLE.optionalFieldOf("attackSpeed").forGetter(Values::attackSpeed),
                Codec.DOUBLE.optionalFieldOf("armor").forGetter(Values::armor),
                Codec.DOUBLE.optionalFieldOf("toughness").forGetter(Values::toughness),
                Codec.DOUBLE.optionalFieldOf("knockbackResistance").forGetter(Values::knockbackResistance)
        ).apply(i, Values::new));

        public boolean isEmpty() {
            return attackDamage.isEmpty() && attackSpeed.isEmpty() && armor.isEmpty() && toughness.isEmpty()
                    && knockbackResistance.isEmpty();
        }
    }

    /** Field name in JSON, getter and allowed range (all minimums are 0). */
    private record Field(String name, Function<Values, Optional<Double>> get, double max) {}

    private static final List<Field> FIELDS = List.of(
            new Field("attackDamage", Values::attackDamage, MAX_ATTACK_DAMAGE),
            new Field("attackSpeed", Values::attackSpeed, MAX_ATTACK_SPEED),
            new Field("armor", Values::armor, MAX_ARMOR),
            new Field("toughness", Values::toughness, MAX_TOUGHNESS),
            new Field("knockbackResistance", Values::knockbackResistance, MAX_KNOCKBACK_RESISTANCE));

    public static final Codec<EquipmentConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Values.CODEC).optionalFieldOf("items", Map.of())
                    .forGetter(EquipmentConfig::items)
    ).apply(i, EquipmentConfig::new));

    /** Server → client copy (tooltips must show the same numbers the server applies). */
    public static final StreamCodec<ByteBuf, EquipmentConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 22).map(
            EquipmentConfig::fromJsonString, EquipmentConfig::toJsonString);

    public EquipmentConfig {
        items = new TreeMap<>(items);
    }

    @Override
    public Map<String, Values> items() {
        return Collections.unmodifiableMap(items);
    }

    public static String toJsonString(EquipmentConfig c) {
        return CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString();
    }

    public static EquipmentConfig fromJsonString(String s) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new);
    }

    public Optional<Values> get(ResourceLocation item) {
        return Optional.ofNullable(items.get(item.toString()));
    }

    /** "{@code key} {@code field} {@code value} must be 0..{@code max}" for each NaN / out-of-range value. */
    public List<String> rangeErrors() {
        List<String> out = new ArrayList<>();
        items.forEach((key, v) -> {
            for (Field f : FIELDS) {
                Optional<Double> d = f.get().apply(v);
                if (d.isPresent() && !inRange(d.get(), f.max())) {
                    out.add(key + " " + f.name() + " " + d.get() + " must be 0.." + fmt(f.max()));
                }
            }
        });
        return out;
    }

    /** Drops keys that are not item ids and rows with no value, clamps values (one warning each). */
    public EquipmentConfig sanitized(List<String> warnings) {
        Map<String, Values> out = new TreeMap<>();
        items.forEach((key, v) -> {
            if (ResourceLocation.tryParse(key) == null) {
                warnings.add(FILE + ": " + key + " is not an item id (skipped)");
                return;
            }
            if (v.isEmpty()) return;
            List<Optional<Double>> c = new ArrayList<>();
            for (Field f : FIELDS) c.add(clamp(key, f, f.get().apply(v), warnings));
            out.put(key, new Values(c.get(0), c.get(1), c.get(2), c.get(3), c.get(4)));
        });
        EquipmentConfig c = new EquipmentConfig(out);
        return c.equals(this) ? this : c;
    }

    private static boolean inRange(double d, double max) {
        return !Double.isNaN(d) && d >= 0 && d <= max;
    }

    private static Optional<Double> clamp(String key, Field f, Optional<Double> v, List<String> warnings) {
        if (v.isEmpty() || inRange(v.get(), f.max())) return v;
        double d = v.get();
        double c = Double.isNaN(d) ? 0 : Math.max(0, Math.min(f.max(), d));
        warnings.add(FILE + ": " + key + " " + f.name() + " " + d + " clamped to " + c);
        return Optional.of(c);
    }

    private static String fmt(double d) {
        return d == Math.rint(d) ? Long.toString((long) d) : Double.toString(d);
    }
}
