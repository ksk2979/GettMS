package org.gettserver.gettcore.enchant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.enchant.EnchantConfig.Tiered;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.LevelWeights;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;

/**
 * {@code enchant.json} 의 {@code armor}: 방어구 특수 능력의 뽑기 확률과 수치.
 * 빠진 필드는 기본값; 잘못된 값은 {@link #sanitized}가 필드별로 기본값으로 바꾼다.
 *
 * @param abilityWeights 부위({@code helmet} 등) → 능력 id → 뽑힐 가중치. 빠진 능력은 기본 가중치.
 * @param leap           점프 강화 단계(1단계마다 점프 +약 반 칸, 안전 낙하 +1칸)
 */
public record ArmorConfig(LevelWeights levelWeights, Map<String, Map<String, Integer>> abilityWeights, Tiered swift,
                          double gogglesSeconds, Tiered sense, Tiered vitality, Regen regen, Undying undying,
                          Tiered dodge, Tiered leap, Tiered steadfast, Landing landing) {

    /** 마지막 피해 뒤 {@code idleSeconds}가 지나면 {@code intervalSeconds}마다 체력 1(반 칸) 회복. */
    public record Regen(double idleSeconds, Tiered intervalSeconds) {
        static final Regen DEFAULT = new Regen(5, new Tiered(3, 2, 1));
        static final Codec<Regen> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("idleSeconds", DEFAULT.idleSeconds).forGetter(Regen::idleSeconds),
                Tiered.CODEC.optionalFieldOf("intervalSeconds", DEFAULT.intervalSeconds).forGetter(Regen::intervalSeconds)
        ).apply(i, Regen::new));
    }

    public record Undying(Tiered invulnerableSeconds, Tiered cooldownSeconds) {
        static final Undying DEFAULT = new Undying(new Tiered(2, 3, 5), new Tiered(300, 300, 300));
        static final Codec<Undying> CODEC = RecordCodecBuilder.create(i -> i.group(
                Tiered.SCALAR_OR_LIST.optionalFieldOf("invulnerableSeconds", DEFAULT.invulnerableSeconds)
                        .forGetter(Undying::invulnerableSeconds),
                Tiered.SCALAR_OR_LIST.optionalFieldOf("cooldownSeconds", DEFAULT.cooldownSeconds)
                        .forGetter(Undying::cooldownSeconds)
        ).apply(i, Undying::new));
    }

    /** 낙하 피해의 {@code percent}%를 반경 {@code radius}칸 안의 적에게. */
    public record Landing(Tiered percent, double radius) {
        static final Landing DEFAULT = new Landing(new Tiered(30, 60, 100), 3);
        static final Codec<Landing> CODEC = RecordCodecBuilder.create(i -> i.group(
                Tiered.CODEC.optionalFieldOf("percent", DEFAULT.percent).forGetter(Landing::percent),
                Codec.DOUBLE.optionalFieldOf("radius", DEFAULT.radius).forGetter(Landing::radius)
        ).apply(i, Landing::new));
    }

    private static Map<String, Map<String, Integer>> defaultWeights() {
        Map<String, Map<String, Integer>> m = new LinkedHashMap<>();
        m.put("helmet", ordered("swift", 40, "goggles", 30, "sense", 20, "nightvision", 10));
        m.put("chestplate", ordered("swift", 40, "vitality", 30, "regen", 20, "undying", 10));
        m.put("leggings", ordered("swift", 40, "leap", 30, "steadfast", 20, "dodge", 10));
        m.put("boots", ordered("swift", 40, "farmstep", 30, "climb", 20, "landing", 10));
        return m;
    }

    private static Map<String, Integer> ordered(Object... kv) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], (Integer) kv[i + 1]);
        return m;
    }

    public static final ArmorConfig DEFAULT = new ArmorConfig(LevelWeights.ARMOR_DEFAULT, defaultWeights(),
            new Tiered(5, 10, 20), 10, new Tiered(8, 12, 16), new Tiered(2, 4, 6), Regen.DEFAULT, Undying.DEFAULT,
            new Tiered(5, 10, 15), new Tiered(1, 2, 3), new Tiered(20, 40, 60), Landing.DEFAULT);

    private static final Codec<LevelWeights> WEIGHTS_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("1", LevelWeights.ARMOR_DEFAULT.one()).forGetter(LevelWeights::one),
            Codec.INT.optionalFieldOf("2", LevelWeights.ARMOR_DEFAULT.two()).forGetter(LevelWeights::two),
            Codec.INT.optionalFieldOf("3", LevelWeights.ARMOR_DEFAULT.three()).forGetter(LevelWeights::three)
    ).apply(i, LevelWeights::new));

    public static final Codec<ArmorConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            WEIGHTS_CODEC.optionalFieldOf("levelWeights", DEFAULT.levelWeights).forGetter(ArmorConfig::levelWeights),
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.INT))
                    .optionalFieldOf("abilityWeights", DEFAULT.abilityWeights).forGetter(ArmorConfig::abilityWeights),
            EnchantConfig.tieredSection("swift", "percent", DEFAULT.swift).forGetter(ArmorConfig::swift),
            Codec.DOUBLE.optionalFieldOf("seconds", DEFAULT.gogglesSeconds).codec()
                    .optionalFieldOf("goggles", DEFAULT.gogglesSeconds).forGetter(ArmorConfig::gogglesSeconds),
            EnchantConfig.tieredSection("sense", "range", DEFAULT.sense).forGetter(ArmorConfig::sense),
            EnchantConfig.tieredSection("vitality", "health", DEFAULT.vitality).forGetter(ArmorConfig::vitality),
            Regen.CODEC.optionalFieldOf("regen", Regen.DEFAULT).forGetter(ArmorConfig::regen),
            Undying.CODEC.optionalFieldOf("undying", Undying.DEFAULT).forGetter(ArmorConfig::undying),
            EnchantConfig.tieredSection("dodge", "chance", DEFAULT.dodge).forGetter(ArmorConfig::dodge),
            EnchantConfig.tieredSection("leap", "level", DEFAULT.leap).forGetter(ArmorConfig::leap),
            EnchantConfig.tieredSection("steadfast", "percent", DEFAULT.steadfast).forGetter(ArmorConfig::steadfast),
            Landing.CODEC.optionalFieldOf("landing", Landing.DEFAULT).forGetter(ArmorConfig::landing)
    ).apply(i, ArmorConfig::new));

    /** 이 부위에서 이 능력이 뽑힐 가중치. 설정에 없거나 음수이면 기본값, 기본값에도 없으면 0. */
    public int weight(Tool tool, Ability ability) {
        Integer w = abilityWeights.getOrDefault(tool.id(), Map.of()).get(ability.id());
        if (w != null && w >= 0) return w;
        return DEFAULT.abilityWeights.getOrDefault(tool.id(), Map.of()).getOrDefault(ability.id(), 0);
    }

    public ArmorConfig sanitized(List<String> errors) {
        ArmorConfig d = DEFAULT;
        LevelWeights w = levelWeights;
        if (!w.valid()) {
            errors.add("enchant.json: armor.levelWeights " + w.one() + "/" + w.two() + "/" + w.three()
                    + " invalid (negative or sum <= 0), using defaults");
            w = d.levelWeights;
        }
        abilityWeights.forEach((tool, byAbility) -> byAbility.forEach((ability, weight) -> {
            if (weight < 0) {
                errors.add("enchant.json: armor.abilityWeights." + tool + "." + ability + " " + weight
                        + " is negative, using default");
            }
        }));
        return new ArmorConfig(w, abilityWeights,
                EnchantConfig.tiered("armor.swift.percent", swift, d.swift, errors),
                EnchantConfig.scalar("armor.goggles.seconds", gogglesSeconds, d.gogglesSeconds, errors),
                EnchantConfig.tiered("armor.sense.range", sense, d.sense, errors),
                EnchantConfig.tiered("armor.vitality.health", vitality, d.vitality, errors),
                new Regen(EnchantConfig.scalar("armor.regen.idleSeconds", regen.idleSeconds, d.regen.idleSeconds, errors),
                        EnchantConfig.tiered("armor.regen.intervalSeconds", regen.intervalSeconds,
                                d.regen.intervalSeconds, errors)),
                new Undying(EnchantConfig.tiered("armor.undying.invulnerableSeconds", undying.invulnerableSeconds,
                        d.undying.invulnerableSeconds, errors),
                        EnchantConfig.tiered("armor.undying.cooldownSeconds", undying.cooldownSeconds,
                                d.undying.cooldownSeconds, errors)),
                EnchantConfig.tiered("armor.dodge.chance", dodge, d.dodge, errors),
                EnchantConfig.tiered("armor.leap.level", leap, d.leap, errors),
                EnchantConfig.tiered("armor.steadfast.percent", steadfast, d.steadfast, errors),
                new Landing(EnchantConfig.tiered("armor.landing.percent", landing.percent, d.landing.percent, errors),
                        EnchantConfig.scalar("armor.landing.radius", landing.radius, d.landing.radius, errors)));
    }
}
