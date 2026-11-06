package org.gettserver.gettcore.enchant;

import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.LevelWeights;

/**
 * {@code config/gettcore/enchant.json}: 특수 능력 수치. JSON은 능력별 중첩 객체(스펙 §5.4).
 * 빠진 필드는 기본값; 잘못된 값은 {@link #sanitized(List)}가 필드별로 기본값으로 바꾼다.
 */
public record EnchantConfig(LevelWeights levelWeights, Tiered harvest, Tiered bounty, Tiered vein, Tiered timber,
                            Tiered lumber, Tiered sweepRadius, double sweepDamagePercent, Tiered leech, Tiered wisdom,
                            double executeHealthPercent, Tiered executeBonus, Tiered plunder, double volleyAngle,
                            Tiered quickdraw, double homingRange, double homingCone, double homingSteer,
                            boolean homingTargetPlayers, Tiered lure, Tiered bigcatch, Tiered treasure) {

    /** 레벨 I/II/III 값. JSON은 길이 3 배열; 길이가 다르면 NaN으로 읽혀 {@link #sanitized}가 기본값으로 바꾼다. */
    public record Tiered(double one, double two, double three) {
        private static final Tiered INVALID = new Tiered(Double.NaN, Double.NaN, Double.NaN);

        public static final Codec<Tiered> CODEC = Codec.DOUBLE.listOf().xmap(
                l -> l.size() == 3 ? new Tiered(l.get(0), l.get(1), l.get(2)) : INVALID,
                t -> List.of(t.one, t.two, t.three));

        public double get(int level) {
            return switch (level) {
                case 1 -> one;
                case 2 -> two;
                default -> three;
            };
        }

        boolean valid() {
            return valid(one) && valid(two) && valid(three);
        }

        static boolean valid(double v) {
            return Double.isFinite(v) && v >= 0;
        }
    }

    public static final EnchantConfig DEFAULT = new EnchantConfig(LevelWeights.DEFAULT,
            new Tiered(1, 2, 3), new Tiered(10, 20, 35), new Tiered(8, 16, 32), new Tiered(16, 32, 64),
            new Tiered(10, 20, 35), new Tiered(1.5, 2.0, 2.5), 50, new Tiered(5, 10, 15), new Tiered(25, 50, 100),
            20, new Tiered(30, 60, 100), new Tiered(10, 20, 35), 10, new Tiered(15, 30, 45),
            8, 30, 0.1, false, new Tiered(15, 30, 45), new Tiered(10, 20, 35), new Tiered(15, 30, 45));

    private static final Codec<LevelWeights> WEIGHTS_CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.INT.optionalFieldOf("1", LevelWeights.DEFAULT.one()).forGetter(LevelWeights::one),
            Codec.INT.optionalFieldOf("2", LevelWeights.DEFAULT.two()).forGetter(LevelWeights::two),
            Codec.INT.optionalFieldOf("3", LevelWeights.DEFAULT.three()).forGetter(LevelWeights::three)
    ).apply(i, LevelWeights::new));

    /** 값 하나짜리 섹션 {@code {"<key>": [a, b, c]}}을 {@code name} 필드로. */
    private static MapCodec<Tiered> tieredSection(String name, String key, Tiered def) {
        return Tiered.CODEC.optionalFieldOf(key, def).codec().optionalFieldOf(name, def);
    }

    private record Sweep(Tiered radius, double damagePercent) {
        static final Sweep DEFAULT_SWEEP = new Sweep(DEFAULT.sweepRadius(), DEFAULT.sweepDamagePercent());
        static final Codec<Sweep> CODEC = RecordCodecBuilder.create(i -> i.group(
                Tiered.CODEC.optionalFieldOf("radius", DEFAULT_SWEEP.radius).forGetter(Sweep::radius),
                Codec.DOUBLE.optionalFieldOf("damagePercent", DEFAULT_SWEEP.damagePercent).forGetter(Sweep::damagePercent)
        ).apply(i, Sweep::new));
    }

    private record Execute(double healthPercent, Tiered bonusPercent) {
        static final Execute DEFAULT_EXECUTE = new Execute(DEFAULT.executeHealthPercent(), DEFAULT.executeBonus());
        static final Codec<Execute> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("healthPercent", DEFAULT_EXECUTE.healthPercent).forGetter(Execute::healthPercent),
                Tiered.CODEC.optionalFieldOf("bonusPercent", DEFAULT_EXECUTE.bonusPercent).forGetter(Execute::bonusPercent)
        ).apply(i, Execute::new));
    }

    private record Homing(double range, double coneDegrees, double steer, boolean targetPlayers) {
        static final Homing DEFAULT_HOMING = new Homing(DEFAULT.homingRange(), DEFAULT.homingCone(), DEFAULT.homingSteer(),
                DEFAULT.homingTargetPlayers());
        static final Codec<Homing> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.optionalFieldOf("range", DEFAULT_HOMING.range).forGetter(Homing::range),
                Codec.DOUBLE.optionalFieldOf("coneDegrees", DEFAULT_HOMING.coneDegrees).forGetter(Homing::coneDegrees),
                Codec.DOUBLE.optionalFieldOf("steer", DEFAULT_HOMING.steer).forGetter(Homing::steer),
                Codec.BOOL.optionalFieldOf("targetPlayers", DEFAULT_HOMING.targetPlayers).forGetter(Homing::targetPlayers)
        ).apply(i, Homing::new));
    }

    /** levelWeights + 괭이·곡괭이·도끼. RecordCodecBuilder 필드 수 제한(16) 때문에 둘로 나눈다. */
    private record Tools(LevelWeights levelWeights, Tiered harvest, Tiered bounty, Tiered vein, Tiered timber,
                         Tiered lumber) {
        static final MapCodec<Tools> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                WEIGHTS_CODEC.optionalFieldOf("levelWeights", DEFAULT.levelWeights()).forGetter(Tools::levelWeights),
                tieredSection("harvest", "radius", DEFAULT.harvest()).forGetter(Tools::harvest),
                tieredSection("bounty", "chance", DEFAULT.bounty()).forGetter(Tools::bounty),
                tieredSection("vein", "maxBlocks", DEFAULT.vein()).forGetter(Tools::vein),
                tieredSection("timber", "maxBlocks", DEFAULT.timber()).forGetter(Tools::timber),
                tieredSection("lumber", "chance", DEFAULT.lumber()).forGetter(Tools::lumber)
        ).apply(i, Tools::new));
    }

    /** 검·활·낚싯대. */
    private record Combat(Sweep sweep, Tiered leech, Tiered wisdom, Execute execute, Tiered plunder, double volleyAngle,
                          Tiered quickdraw, Homing homing, Tiered lure, Tiered bigcatch, Tiered treasure) {
        static final MapCodec<Combat> CODEC = RecordCodecBuilder.mapCodec(i -> i.group(
                Sweep.CODEC.optionalFieldOf("sweep", Sweep.DEFAULT_SWEEP).forGetter(Combat::sweep),
                tieredSection("leech", "percent", DEFAULT.leech()).forGetter(Combat::leech),
                tieredSection("wisdom", "percent", DEFAULT.wisdom()).forGetter(Combat::wisdom),
                Execute.CODEC.optionalFieldOf("execute", Execute.DEFAULT_EXECUTE).forGetter(Combat::execute),
                tieredSection("plunder", "chance", DEFAULT.plunder()).forGetter(Combat::plunder),
                Codec.DOUBLE.optionalFieldOf("angleDegrees", DEFAULT.volleyAngle()).codec()
                        .optionalFieldOf("volley", DEFAULT.volleyAngle())
                        .forGetter(Combat::volleyAngle),
                tieredSection("quickdraw", "percent", DEFAULT.quickdraw()).forGetter(Combat::quickdraw),
                Homing.CODEC.optionalFieldOf("homing", Homing.DEFAULT_HOMING).forGetter(Combat::homing),
                tieredSection("lure", "percent", DEFAULT.lure()).forGetter(Combat::lure),
                tieredSection("bigcatch", "chance", DEFAULT.bigcatch()).forGetter(Combat::bigcatch),
                tieredSection("treasure", "chance", DEFAULT.treasure()).forGetter(Combat::treasure)
        ).apply(i, Combat::new));
    }

    public static final Codec<EnchantConfig> CODEC = RecordCodecBuilder.<Pair<Tools, Combat>>create(i -> i.group(
            Tools.CODEC.forGetter(Pair::getFirst),
            Combat.CODEC.forGetter(Pair::getSecond)
    ).apply(i, Pair::of)).xmap(EnchantConfig::fromParts, EnchantConfig::toParts);

    private static EnchantConfig fromParts(Pair<Tools, Combat> p) {
        Tools t = p.getFirst();
        Combat c = p.getSecond();
        return new EnchantConfig(t.levelWeights(), t.harvest(), t.bounty(), t.vein(), t.timber(), t.lumber(),
                c.sweep().radius(), c.sweep().damagePercent(), c.leech(), c.wisdom(),
                c.execute().healthPercent(), c.execute().bonusPercent(), c.plunder(), c.volleyAngle(), c.quickdraw(),
                c.homing().range(), c.homing().coneDegrees(), c.homing().steer(), c.homing().targetPlayers(),
                c.lure(), c.bigcatch(), c.treasure());
    }

    private Pair<Tools, Combat> toParts() {
        return Pair.of(new Tools(levelWeights, harvest, bounty, vein, timber, lumber),
                new Combat(new Sweep(sweepRadius, sweepDamagePercent), leech, wisdom,
                        new Execute(executeHealthPercent, executeBonus), plunder, volleyAngle, quickdraw,
                        new Homing(homingRange, homingCone, homingSteer, homingTargetPlayers),
                        lure, bigcatch, treasure));
    }

    /** 음수·NaN·배열 길이 ≠ 3·가중치 합 ≤ 0인 필드를 기본값으로 바꾼 사본. 바꾼 필드마다 {@code errors}에 한 줄. */
    public EnchantConfig sanitized(List<String> errors) {
        EnchantConfig d = DEFAULT;
        LevelWeights w = levelWeights;
        if (w.one() < 0 || w.two() < 0 || w.three() < 0 || (long) w.one() + w.two() + w.three() <= 0) {
            errors.add("enchant.json: levelWeights " + w.one() + "/" + w.two() + "/" + w.three()
                    + " invalid (negative or sum <= 0), using defaults");
            w = d.levelWeights;
        }
        return new EnchantConfig(w,
                tiered("harvest.radius", harvest, d.harvest, errors),
                tiered("bounty.chance", bounty, d.bounty, errors),
                tiered("vein.maxBlocks", vein, d.vein, errors),
                tiered("timber.maxBlocks", timber, d.timber, errors),
                tiered("lumber.chance", lumber, d.lumber, errors),
                tiered("sweep.radius", sweepRadius, d.sweepRadius, errors),
                scalar("sweep.damagePercent", sweepDamagePercent, d.sweepDamagePercent, errors),
                tiered("leech.percent", leech, d.leech, errors),
                tiered("wisdom.percent", wisdom, d.wisdom, errors),
                scalar("execute.healthPercent", executeHealthPercent, d.executeHealthPercent, errors),
                tiered("execute.bonusPercent", executeBonus, d.executeBonus, errors),
                tiered("plunder.chance", plunder, d.plunder, errors),
                scalar("volley.angleDegrees", volleyAngle, d.volleyAngle, errors),
                tiered("quickdraw.percent", quickdraw, d.quickdraw, errors),
                scalar("homing.range", homingRange, d.homingRange, errors),
                scalar("homing.coneDegrees", homingCone, d.homingCone, errors),
                scalar("homing.steer", homingSteer, d.homingSteer, errors),
                homingTargetPlayers,
                tiered("lure.percent", lure, d.lure, errors),
                tiered("bigcatch.chance", bigcatch, d.bigcatch, errors),
                tiered("treasure.chance", treasure, d.treasure, errors));
    }

    private static Tiered tiered(String name, Tiered v, Tiered def, List<String> errors) {
        if (v.valid()) return v;
        errors.add("enchant.json: " + name + " must be 3 non-negative numbers, using default ["
                + def.one + ", " + def.two + ", " + def.three + "]");
        return def;
    }

    private static double scalar(String name, double v, double def, List<String> errors) {
        if (Tiered.valid(v)) return v;
        errors.add("enchant.json: " + name + " " + v + " invalid, using default " + def);
        return def;
    }

    /** 설명 줄에 넣을 대표 수치. 1레벨 능력은 단일 값, 수치가 없는 능력은 0. */
    public double value(Ability a, int level) {
        return switch (a) {
            case HARVEST -> harvest.get(level);
            case BOUNTY -> bounty.get(level);
            case VEIN -> vein.get(level);
            case TIMBER -> timber.get(level);
            case LUMBER -> lumber.get(level);
            case SWEEP -> sweepRadius.get(level);
            case LEECH -> leech.get(level);
            case WISDOM -> wisdom.get(level);
            case EXECUTE -> executeBonus.get(level);
            case PLUNDER -> plunder.get(level);
            case QUICKDRAW -> quickdraw.get(level);
            case LURE -> lure.get(level);
            case BIGCATCH -> bigcatch.get(level);
            case TREASURE -> treasure.get(level);
            case VOLLEY -> volleyAngle;
            case HOMING -> homingRange;
            case REPLANT, AREA, SMELT, RESAPLING -> 0;
        };
    }
}
