package org.gettserver.gettcore.reinforce;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * {@code config/gettcore/reinforce.json}: 장비 강화 수치. {@code successPercent[N]}/{@code stoneCost[N]} 은 단계 N → N+1
 * 강화의 성공률(%)과 필요 강화석 수. 빠진 필드는 기본값; 범위 밖 값은 {@link #sanitized(List)} 가 고친다.
 */
public record ReinforceConfig(int maxLevel, List<Double> successPercent, List<Integer> stoneCost,
                              double gunDamagePercentPerLevel, double meleeDamagePercentPerLevel,
                              double shieldSpeedPercentPerLevel, double armorHealthPerLevel, double armorHealthMax) {

    public static final String FILE = "reinforce.json";

    public static final ReinforceConfig DEFAULT = new ReinforceConfig(20,
            List.of(100.0, 100.0, 100.0, 90.0, 85.0, 80.0, 75.0, 70.0, 65.0, 60.0,
                    55.0, 50.0, 45.0, 40.0, 35.0, 30.0, 25.0, 20.0, 15.0, 10.0),
            List.of(1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1),
            10, 10, 2, 1.5, 60);

    public ReinforceConfig {
        successPercent = List.copyOf(successPercent);
        stoneCost = List.copyOf(stoneCost);
    }

    public static final Codec<ReinforceConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("maxLevel", DEFAULT.maxLevel).forGetter(ReinforceConfig::maxLevel),
            Codec.DOUBLE.listOf().optionalFieldOf("successPercent", DEFAULT.successPercent)
                    .forGetter(ReinforceConfig::successPercent),
            Codec.INT.listOf().optionalFieldOf("stoneCost", DEFAULT.stoneCost).forGetter(ReinforceConfig::stoneCost),
            Codec.DOUBLE.optionalFieldOf("gunDamagePercentPerLevel", DEFAULT.gunDamagePercentPerLevel)
                    .forGetter(ReinforceConfig::gunDamagePercentPerLevel),
            Codec.DOUBLE.optionalFieldOf("meleeDamagePercentPerLevel", DEFAULT.meleeDamagePercentPerLevel)
                    .forGetter(ReinforceConfig::meleeDamagePercentPerLevel),
            Codec.DOUBLE.optionalFieldOf("shieldSpeedPercentPerLevel", DEFAULT.shieldSpeedPercentPerLevel)
                    .forGetter(ReinforceConfig::shieldSpeedPercentPerLevel),
            Codec.DOUBLE.optionalFieldOf("armorHealthPerLevel", DEFAULT.armorHealthPerLevel)
                    .forGetter(ReinforceConfig::armorHealthPerLevel),
            Codec.DOUBLE.optionalFieldOf("armorHealthMax", DEFAULT.armorHealthMax)
                    .forGetter(ReinforceConfig::armorHealthMax)
    ).apply(i, ReinforceConfig::new));

    /** Sent over the wire as the JSON text (the config is small; the client re-parses it). */
    public static final StreamCodec<ByteBuf, ReinforceConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(
            s -> CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new),
            c -> CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString());

    /**
     * Copy with out-of-range values fixed; one line in {@code warnings} per changed field. maxLevel outside 1..50
     * takes the default (20). successPercent/stoneCost longer than maxLevel are cut, shorter ones are padded with their
     * last value (an empty list takes the default list's value at that index, or its last value past its end). Each
     * successPercent is clamped to 0..100 (NaN takes the default at that index), each stoneCost to 1..64. The per-level
     * percents, armorHealthPerLevel and armorHealthMax must be finite and &gt;= 0, else their default.
     */
    public ReinforceConfig sanitized(List<String> warnings) {
        int max = maxLevel;
        if (max < 1 || max > 50) {
            warnings.add(FILE + ": maxLevel " + max + " must be within [1, 50], using default " + DEFAULT.maxLevel);
            max = DEFAULT.maxLevel;
        }
        List<Double> success = resized("successPercent", successPercent, DEFAULT.successPercent, max, warnings);
        List<Double> successFixed = new ArrayList<>(max);
        boolean successClamped = false;
        for (int n = 0; n < max; n++) {
            double v = success.get(n);
            double fixed = Double.isNaN(v) ? defaultAt(DEFAULT.successPercent, n) : Math.max(0, Math.min(100, v));
            if (Double.compare(fixed, v) != 0) successClamped = true;
            successFixed.add(fixed);
        }
        if (successClamped) warnings.add(FILE + ": successPercent values must be within [0, 100], clamped");
        List<Integer> cost = resized("stoneCost", stoneCost, DEFAULT.stoneCost, max, warnings);
        List<Integer> costFixed = new ArrayList<>(max);
        boolean costClamped = false;
        for (int n = 0; n < max; n++) {
            int v = cost.get(n);
            int fixed = Math.max(1, Math.min(64, v));
            if (fixed != v) costClamped = true;
            costFixed.add(fixed);
        }
        if (costClamped) warnings.add(FILE + ": stoneCost values must be within [1, 64], clamped");
        return new ReinforceConfig(max, successFixed, costFixed,
                nonNegative("gunDamagePercentPerLevel", gunDamagePercentPerLevel,
                        DEFAULT.gunDamagePercentPerLevel, warnings),
                nonNegative("meleeDamagePercentPerLevel", meleeDamagePercentPerLevel,
                        DEFAULT.meleeDamagePercentPerLevel, warnings),
                nonNegative("shieldSpeedPercentPerLevel", shieldSpeedPercentPerLevel,
                        DEFAULT.shieldSpeedPercentPerLevel, warnings),
                nonNegative("armorHealthPerLevel", armorHealthPerLevel, DEFAULT.armorHealthPerLevel, warnings),
                nonNegative("armorHealthMax", armorHealthMax, DEFAULT.armorHealthMax, warnings));
    }

    private static <T> List<T> resized(String name, List<T> list, List<T> def, int size, List<String> warnings) {
        if (list.size() == size) return list;
        List<T> out = new ArrayList<>(size);
        for (int n = 0; n < size; n++) {
            if (n < list.size()) out.add(list.get(n));
            else if (!list.isEmpty()) out.add(list.get(list.size() - 1));
            else out.add(defaultAt(def, n));
        }
        warnings.add(FILE + ": " + name + " has " + list.size() + " entries but maxLevel is " + size
                + (list.size() > size ? ", extra entries ignored" : ", missing entries filled with the last value"));
        return out;
    }

    private static <T> T defaultAt(List<T> def, int n) {
        return def.get(Math.min(n, def.size() - 1));
    }

    private static double nonNegative(String name, double v, double def, List<String> warnings) {
        if (Double.isFinite(v) && v >= 0) return v;
        warnings.add(FILE + ": " + name + " " + v + " must be >= 0, using default " + def);
        return def;
    }
}
