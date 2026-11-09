package org.gettserver.gettcore.reinforce;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Predicate;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * {@code config/gettcore/reinforce.json}: 장비 강화 수치. {@code successPercent[N]}/{@code stoneCost[N]} 은 단계 N → N+1
 * 강화의 성공률(%)과 필요 강화석 수. 빠진 필드는 기본값; 범위 밖 값은 {@link #sanitized(List)} 가 고친다.
 */
public record ReinforceConfig(int maxLevel, List<Double> successPercent, List<Integer> stoneCost,
                              List<Long> goldCost,
                              double gunDamagePercentPerLevel, double meleeDamagePercentPerLevel,
                              double shieldSpeedPercentPerLevel, double armorHealthPerLevel, double armorHealthMax,
                              double armorDefensePerLevel, long enchantGoldCost, Elytra elytra,
                              List<Milestone> milestones) {

    public static final String FILE = "reinforce.json";

    /** 강화석 개수 상한(칸 제한이 없어져 64 → 999). */
    public static final int MAX_STONES = 999;
    /** 골드 비용 상한. */
    public static final long MAX_GOLD = 999_999_999L;

    /** 방어구 부위당 방어(ARMOR 속성) 증가 기본값/단계: 30강 4부위 = +6. */
    static final double DEFAULT_ARMOR_DEFENSE = 0.05;
    /** 마법부여 1회 골드 기본값. */
    static final long DEFAULT_ENCHANT_GOLD = 1000;

    /** 기본 골드 비용 단계 n (n → n+1): 100 × 1.25^n 을 10 단위로 반올림. */
    public static long defaultGoldAt(int n) {
        return Math.round(100 * Math.pow(1.25, n) / 10) * 10;
    }

    /** 기본 골드 비용 표(길이 size). */
    public static List<Long> defaultGold(int size) {
        List<Long> out = new ArrayList<>(size);
        for (int n = 0; n < size; n++) out.add(defaultGoldAt(n));
        return List.copyOf(out);
    }

    static final List<Long> DEFAULT_GOLD = defaultGold(30);

    /**
     * 겉날개 강화: 0→1강 비용만 따로({@code firstStoneCost}/{@code firstGoldCost}), 성공률과 2강 이후 비용은 공통 표. 비행
     * 속도 +(N-1)×speedPercentPerLevel %, {@code fireworkBoostLevel} 단계 이상이면 폭죽 부스트 +fireworkBoostPercent %.
     */
    public record Elytra(int firstStoneCost, long firstGoldCost, double speedPercentPerLevel, int fireworkBoostLevel,
                         double fireworkBoostPercent) {
        public static final Elytra DEFAULT = new Elytra(32, 50_000, 2, 15, 50);

        public static final Codec<Elytra> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.INT.optionalFieldOf("firstStoneCost", DEFAULT.firstStoneCost).forGetter(Elytra::firstStoneCost),
                Codec.LONG.optionalFieldOf("firstGoldCost", DEFAULT.firstGoldCost).forGetter(Elytra::firstGoldCost),
                Codec.DOUBLE.optionalFieldOf("speedPercentPerLevel", DEFAULT.speedPercentPerLevel)
                        .forGetter(Elytra::speedPercentPerLevel),
                Codec.INT.optionalFieldOf("fireworkBoostLevel", DEFAULT.fireworkBoostLevel)
                        .forGetter(Elytra::fireworkBoostLevel),
                Codec.DOUBLE.optionalFieldOf("fireworkBoostPercent", DEFAULT.fireworkBoostPercent)
                        .forGetter(Elytra::fireworkBoostPercent)
        ).apply(i, Elytra::new));

        Elytra sanitized(List<String> warnings) {
            int stones = Math.max(1, Math.min(MAX_STONES, firstStoneCost));
            if (stones != firstStoneCost) {
                warnings.add(FILE + ": elytra.firstStoneCost must be within [1, " + MAX_STONES + "], clamped");
            }
            long gold = Math.max(0, Math.min(MAX_GOLD, firstGoldCost));
            if (gold != firstGoldCost) {
                warnings.add(FILE + ": elytra.firstGoldCost must be within [0, " + MAX_GOLD + "], clamped");
            }
            int boostLevel = fireworkBoostLevel;
            if (boostLevel < 1 || boostLevel > MAX_LEVEL_LIMIT) {
                warnings.add(FILE + ": elytra.fireworkBoostLevel " + boostLevel + " must be within [1, "
                        + MAX_LEVEL_LIMIT + "], using default " + DEFAULT.fireworkBoostLevel);
                boostLevel = DEFAULT.fireworkBoostLevel;
            }
            return new Elytra(stones, gold,
                    nonNegative("elytra.speedPercentPerLevel", speedPercentPerLevel, DEFAULT.speedPercentPerLevel,
                            warnings),
                    boostLevel,
                    nonNegative("elytra.fireworkBoostPercent", fireworkBoostPercent, DEFAULT.fireworkBoostPercent,
                            warnings));
        }
    }

    public static final ReinforceConfig DEFAULT = new ReinforceConfig(30,
            List.of(100.0, 100.0, 100.0, 90.0, 85.0, 80.0, 75.0, 70.0, 65.0, 60.0,
                    55.0, 50.0, 45.0, 40.0, 35.0, 30.0, 25.0, 20.0, 15.0, 10.0,
                    10.0, 9.0, 9.0, 8.0, 8.0, 7.0, 7.0, 6.0, 6.0, 5.0),
            Collections.nCopies(30, 1), DEFAULT_GOLD,
            10, 10, 1.7, 0.5, 60, DEFAULT_ARMOR_DEFENSE, DEFAULT_ENCHANT_GOLD, Elytra.DEFAULT, List.of());

    /** 최대 단계 상한(sanitize 가 허용하는 maxLevel 의 최댓값). */
    public static final int MAX_LEVEL_LIMIT = 50;

    /**
     * 효과 id 를 단계 능력에 쓸 수 있는지(sanitize 의 milestone 검사): 등록된 효과이고 즉시 효과(즉시 회복·즉시 피해·포만감)가
     * 아니어야 한다. 즉시 효과는 1초마다 다시 걸면 매 틱 발동한다. 레지스트리가 없는 단위 테스트에서는 항상 true;
     * GettCore 가 시작할 때 MOB_EFFECT 레지스트리 조회로 바꾼다.
     */
    public static volatile Predicate<ResourceLocation> effectUsable = id -> true;

    /**
     * 단계 능력: {@code target} 분류의 장비가 {@code level} 단계 이상이면 {@code effect} 포션 효과(세기 {@code amplifier},
     * 0 = I). 코덱은 모르는 target/잘못된 effect id 를 null 로 읽어 sanitize 가 경고와 함께 버리게 한다.
     */
    public record Milestone(ReinforceRules.Target target, int level, ResourceLocation effect, int amplifier) {
        public static final Codec<Milestone> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.optionalFieldOf("target", "").forGetter(m -> targetName(m.target)),
                Codec.INT.optionalFieldOf("level", 0).forGetter(Milestone::level),
                Codec.STRING.optionalFieldOf("effect", "")
                        .forGetter(m -> m.effect == null ? "" : m.effect.toString()),
                Codec.INT.optionalFieldOf("amplifier", 0).forGetter(Milestone::amplifier)
        ).apply(i, (t, l, e, a) -> new Milestone(parseTarget(t), l, ResourceLocation.tryParse(e), a)));

        /** {@code "gun"} 등 소문자 이름(null → 빈 문자열). */
        public static String targetName(ReinforceRules.Target t) {
            return t == null ? "" : t.name().toLowerCase(Locale.ROOT);
        }

        /** 소문자 이름 → 분류, 모르면 null. */
        public static ReinforceRules.Target parseTarget(String s) {
            for (ReinforceRules.Target t : ReinforceRules.Target.values()) {
                if (t.name().toLowerCase(Locale.ROOT).equals(s)) return t;
            }
            return null;
        }
    }

    public ReinforceConfig {
        successPercent = List.copyOf(successPercent);
        stoneCost = List.copyOf(stoneCost);
        goldCost = List.copyOf(goldCost);
        milestones = List.copyOf(milestones);
    }

    /** Without milestones (empty list), default armor defense. */
    public ReinforceConfig(int maxLevel, List<Double> successPercent, List<Integer> stoneCost,
                           double gunDamagePercentPerLevel, double meleeDamagePercentPerLevel,
                           double shieldSpeedPercentPerLevel, double armorHealthPerLevel, double armorHealthMax) {
        this(maxLevel, successPercent, stoneCost, gunDamagePercentPerLevel, meleeDamagePercentPerLevel,
                shieldSpeedPercentPerLevel, armorHealthPerLevel, armorHealthMax, List.of());
    }

    /** Default armor defense. */
    public ReinforceConfig(int maxLevel, List<Double> successPercent, List<Integer> stoneCost,
                           double gunDamagePercentPerLevel, double meleeDamagePercentPerLevel,
                           double shieldSpeedPercentPerLevel, double armorHealthPerLevel, double armorHealthMax,
                           List<Milestone> milestones) {
        this(maxLevel, successPercent, stoneCost, defaultGold(Math.max(1, Math.min(MAX_LEVEL_LIMIT, maxLevel))), gunDamagePercentPerLevel, meleeDamagePercentPerLevel,
                shieldSpeedPercentPerLevel, armorHealthPerLevel, armorHealthMax, DEFAULT_ARMOR_DEFENSE,
                DEFAULT_ENCHANT_GOLD, Elytra.DEFAULT, milestones);
    }

    public static final Codec<ReinforceConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("maxLevel", DEFAULT.maxLevel).forGetter(ReinforceConfig::maxLevel),
            Codec.DOUBLE.listOf().optionalFieldOf("successPercent", DEFAULT.successPercent)
                    .forGetter(ReinforceConfig::successPercent),
            Codec.INT.listOf().optionalFieldOf("stoneCost", DEFAULT.stoneCost).forGetter(ReinforceConfig::stoneCost),
            Codec.LONG.listOf().optionalFieldOf("goldCost", DEFAULT_GOLD).forGetter(ReinforceConfig::goldCost),
            Codec.DOUBLE.optionalFieldOf("gunDamagePercentPerLevel", DEFAULT.gunDamagePercentPerLevel)
                    .forGetter(ReinforceConfig::gunDamagePercentPerLevel),
            Codec.DOUBLE.optionalFieldOf("meleeDamagePercentPerLevel", DEFAULT.meleeDamagePercentPerLevel)
                    .forGetter(ReinforceConfig::meleeDamagePercentPerLevel),
            Codec.DOUBLE.optionalFieldOf("shieldSpeedPercentPerLevel", DEFAULT.shieldSpeedPercentPerLevel)
                    .forGetter(ReinforceConfig::shieldSpeedPercentPerLevel),
            Codec.DOUBLE.optionalFieldOf("armorHealthPerLevel", DEFAULT.armorHealthPerLevel)
                    .forGetter(ReinforceConfig::armorHealthPerLevel),
            Codec.DOUBLE.optionalFieldOf("armorHealthMax", DEFAULT.armorHealthMax)
                    .forGetter(ReinforceConfig::armorHealthMax),
            Codec.DOUBLE.optionalFieldOf("armorDefensePerLevel", DEFAULT_ARMOR_DEFENSE)
                    .forGetter(ReinforceConfig::armorDefensePerLevel),
            Codec.LONG.optionalFieldOf("enchantGoldCost", DEFAULT_ENCHANT_GOLD)
                    .forGetter(ReinforceConfig::enchantGoldCost),
            Elytra.CODEC.optionalFieldOf("elytra", Elytra.DEFAULT).forGetter(ReinforceConfig::elytra),
            Milestone.CODEC.listOf().optionalFieldOf("milestones", DEFAULT.milestones)
                    .forGetter(ReinforceConfig::milestones)
    ).apply(i, ReinforceConfig::new));

    /** Sent over the wire as the JSON text (the config is small; the client re-parses it). */
    public static final StreamCodec<ByteBuf, ReinforceConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 20).map(
            s -> CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new),
            c -> CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString());

    /**
     * Copy with out-of-range values fixed; one line in {@code warnings} per changed field. maxLevel outside 1..50
     * takes the default (30). successPercent/stoneCost longer than maxLevel are cut, shorter ones are padded with their
     * last value (an empty list takes the default list's value at that index, or its last value past its end). Each
     * successPercent is clamped to 0..100 (NaN takes the default at that index), each stoneCost to 1..999, each goldCost to 0..999,999,999 (a missing goldCost is the default formula for any maxLevel, without warnings). The per-level
     * percents, armorHealthPerLevel, armorHealthMax and armorDefensePerLevel must be finite and &gt;= 0, else their default. A milestone
     * whose target is unknown, level is outside 1..maxLevel, amplifier outside 0..9 or effect is not in
     * {@link #effectUsable} (unknown or instant) is dropped with a warning.
     */
    public ReinforceConfig sanitized(List<String> warnings) {
        int max = maxLevel;
        if (max < 1 || max > MAX_LEVEL_LIMIT) {
            warnings.add(FILE + ": maxLevel " + max + " must be within [1, " + MAX_LEVEL_LIMIT + "], using default "
                    + DEFAULT.maxLevel);
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
            int fixed = Math.max(1, Math.min(MAX_STONES, v));
            if (fixed != v) costClamped = true;
            costFixed.add(fixed);
        }
        if (costClamped) warnings.add(FILE + ": stoneCost values must be within [1, " + MAX_STONES + "], clamped");
        List<Long> goldFixed;
        if (!goldCost.isEmpty() && goldCost.equals(defaultGold(goldCost.size()))) {
            goldFixed = defaultGold(max);
        } else {
            List<Long> gold = resized("goldCost", goldCost, DEFAULT_GOLD, max, warnings);
            goldFixed = new ArrayList<>(max);
            boolean goldClamped = false;
            for (int n = 0; n < max; n++) {
                long v = gold.get(n);
                long fixed = Math.max(0, Math.min(MAX_GOLD, v));
                if (fixed != v) goldClamped = true;
                goldFixed.add(fixed);
            }
            if (goldClamped) warnings.add(FILE + ": goldCost values must be within [0, " + MAX_GOLD + "], clamped");
        }
        long enchantGold = enchantGoldCost;
        if (enchantGold < 0 || enchantGold > MAX_GOLD) {
            warnings.add(FILE + ": enchantGoldCost " + enchantGold + " must be within [0, " + MAX_GOLD
                    + "], using default " + DEFAULT_ENCHANT_GOLD);
            enchantGold = DEFAULT_ENCHANT_GOLD;
        }
        return new ReinforceConfig(max, successFixed, costFixed, goldFixed,
                nonNegative("gunDamagePercentPerLevel", gunDamagePercentPerLevel,
                        DEFAULT.gunDamagePercentPerLevel, warnings),
                nonNegative("meleeDamagePercentPerLevel", meleeDamagePercentPerLevel,
                        DEFAULT.meleeDamagePercentPerLevel, warnings),
                nonNegative("shieldSpeedPercentPerLevel", shieldSpeedPercentPerLevel,
                        DEFAULT.shieldSpeedPercentPerLevel, warnings),
                nonNegative("armorHealthPerLevel", armorHealthPerLevel, DEFAULT.armorHealthPerLevel, warnings),
                nonNegative("armorHealthMax", armorHealthMax, DEFAULT.armorHealthMax, warnings),
                nonNegative("armorDefensePerLevel", armorDefensePerLevel, DEFAULT_ARMOR_DEFENSE, warnings),
                enchantGold, elytra.sanitized(warnings),
                sanitizedMilestones(milestones, max, warnings));
    }

    private static List<Milestone> sanitizedMilestones(List<Milestone> list, int max, List<String> warnings) {
        List<Milestone> out = new ArrayList<>(list.size());
        Predicate<ResourceLocation> exists = effectUsable;
        for (int n = 0; n < list.size(); n++) {
            Milestone m = list.get(n);
            String problem = milestoneProblem(m, max, exists);
            if (problem == null) {
                out.add(m);
            } else {
                warnings.add(FILE + ": milestones[" + n + "] " + problem + ", removed");
            }
        }
        return out;
    }

    private static String milestoneProblem(Milestone m, int max, Predicate<ResourceLocation> exists) {
        if (m.target() == null || m.target() == ReinforceRules.Target.ELYTRA) {
            return "target must be gun, melee, shield or armor";
        }
        if (m.level() < 1 || m.level() > max) return "level " + m.level() + " must be within [1, " + max + "]";
        if (m.amplifier() < 0 || m.amplifier() > 9) return "amplifier " + m.amplifier() + " must be within [0, 9]";
        if (m.effect() == null) return "effect id is invalid";
        if (!exists.test(m.effect())) {
            return "effect " + m.effect() + " is unknown or instant (즉시 효과는 쓸 수 없습니다)";
        }
        return null;
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
