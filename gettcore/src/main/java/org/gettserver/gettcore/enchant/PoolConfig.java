package org.gettserver.gettcore.enchant;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.gettserver.gettcore.enchant.SpecialEnchants.Ability;
import org.gettserver.gettcore.enchant.SpecialEnchants.Tool;

/**
 * {@code enchant.json}의 {@code pool}: 도구별 뽑기 목록. 단일 능력의 가중치, 묶음(능력 여러 개를 한 번에 붙이는
 * 뽑기 결과), 레벨만 다시 뽑기 설정. 묶음은 등록된 인챈트가 아니라 설정일 뿐이라 서버 재시작 없이 바뀐다.
 *
 * @param weights 도구({@code hoe} 등) → 능력 id → 단일로 뽑힐 가중치. 빠진 능력은 {@link #SINGLE_WEIGHT}.
 * @param bundles 도구 → 묶음 목록. 도구 키가 아예 없으면 기본 목록, 빈 목록이면 묶음 없음. 방어구는 묶음이 없다.
 */
public record PoolConfig(Map<String, Map<String, Integer>> weights, Map<String, List<Bundle>> bundles,
                         LevelReroll levelReroll) {
    public static final int SINGLE_WEIGHT = 10;
    private static final int PAIR_WEIGHT = 3;
    private static final int TRIPLE_WEIGHT = 2;
    private static final int MASTER_WEIGHT = 1;

    /**
     * @param abilities 능력 id 2개 이상. 그 도구에 붙는 능력만, 중복 없이.
     * @param name      표시 이름. 비어 있으면 능력 이름을 {@code ·}로 이어 붙인다.
     */
    public record Bundle(String id, List<String> abilities, int weight, String name) {
        public static final Codec<Bundle> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Bundle::id),
                Codec.STRING.listOf().fieldOf("abilities").forGetter(Bundle::abilities),
                Codec.INT.optionalFieldOf("weight", PAIR_WEIGHT).forGetter(Bundle::weight),
                Codec.STRING.optionalFieldOf("name", "").forGetter(Bundle::name)
        ).apply(i, Bundle::new));

        /** 아는 능력만 모은 집합(모르는 id는 빠진다). */
        public Set<Ability> abilitySet() {
            Set<Ability> set = EnumSet.noneOf(Ability.class);
            for (String id : abilities) Ability.byId(id).ifPresent(set::add);
            return set;
        }

        /** {@link Ability} 선언 순서로 정렬한 능력 목록. */
        public List<Ability> abilityList() {
            return List.copyOf(abilitySet());
        }
    }

    /** 능력 구성은 그대로 두고 레벨만 다시 뽑는 기능. 기본은 꺼져 있다. */
    public record LevelReroll(boolean enabled, long goldCost, int stoneCost) {
        public static final LevelReroll DEFAULT = new LevelReroll(false, 1000, 1);
        public static final Codec<LevelReroll> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.BOOL.optionalFieldOf("enabled", DEFAULT.enabled).forGetter(LevelReroll::enabled),
                Codec.LONG.optionalFieldOf("goldCost", DEFAULT.goldCost).forGetter(LevelReroll::goldCost),
                Codec.INT.optionalFieldOf("stoneCost", DEFAULT.stoneCost).forGetter(LevelReroll::stoneCost)
        ).apply(i, LevelReroll::new));
    }

    public static final PoolConfig DEFAULT = new PoolConfig(defaultWeights(), defaultBundles(), LevelReroll.DEFAULT);

    public static final Codec<PoolConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Codec.unboundedMap(Codec.STRING, Codec.INT))
                    .optionalFieldOf("weights", Map.of()).forGetter(PoolConfig::weights),
            Codec.unboundedMap(Codec.STRING, Bundle.CODEC.listOf())
                    .optionalFieldOf("bundles", Map.of()).forGetter(PoolConfig::bundles),
            LevelReroll.CODEC.optionalFieldOf("levelReroll", LevelReroll.DEFAULT).forGetter(PoolConfig::levelReroll)
    ).apply(i, PoolConfig::new));

    private static Map<String, Map<String, Integer>> defaultWeights() {
        Map<String, Map<String, Integer>> m = new LinkedHashMap<>();
        for (Tool tool : Tool.values()) {
            if (tool.isArmor()) continue;
            Map<String, Integer> byAbility = new LinkedHashMap<>();
            for (Ability a : SpecialEnchants.pool(tool)) byAbility.put(a.id(), SINGLE_WEIGHT);
            m.put(tool.id(), byAbility);
        }
        return m;
    }

    private static Map<String, List<Bundle>> defaultBundles() {
        Map<String, List<Bundle>> m = new LinkedHashMap<>();
        m.put("hoe", List.of(
                pair("harvest", "replant"), pair("harvest", "bounty"), pair("bounty", "replant"),
                master("master_farmer", "최강의 농부", "harvest", "replant", "bounty")));
        m.put("pickaxe", List.of(
                pair("vein", "prospect"), pair("vein", "smelt"), pair("area", "smelt"),
                new Bundle("vein_prospect_area", List.of("vein", "prospect", "area"), TRIPLE_WEIGHT, ""),
                master("master_miner", "최강의 광부", "vein", "prospect", "area", "smelt")));
        m.put("axe", List.of(
                pair("timber", "resapling"), pair("timber", "lumber"), pair("lumber", "resapling"),
                master("master_lumberjack", "최강의 나무꾼", "timber", "resapling", "lumber")));
        m.put("sword", List.of(
                pair("sweep", "leech"), pair("sweep", "execute"), pair("leech", "execute"), pair("wisdom", "plunder"),
                master("master_swordsman", "최강의 검사", "sweep", "leech", "execute")));
        m.put("bow", List.of(
                pair("volley", "quickdraw"), pair("volley", "homing"), pair("quickdraw", "homing"),
                master("master_archer", "최강의 궁수", "volley", "quickdraw", "homing")));
        m.put("fishing_rod", List.of(
                pair("lure", "bigcatch"), pair("lure", "treasure"), pair("bigcatch", "treasure"),
                master("master_angler", "최강의 낚시꾼", "lure", "bigcatch", "treasure")));
        return m;
    }

    private static Bundle pair(String a, String b) {
        return new Bundle(a + "_" + b, List.of(a, b), PAIR_WEIGHT, "");
    }

    private static Bundle master(String id, String name, String... abilities) {
        return new Bundle(id, List.of(abilities), MASTER_WEIGHT, name);
    }

    /** 이 도구에서 이 능력이 단일로 뽑힐 가중치. 설정에 없거나 음수이면 {@link #SINGLE_WEIGHT}. */
    public int weight(Tool tool, Ability ability) {
        Integer w = weights.getOrDefault(tool.id(), Map.of()).get(ability.id());
        return w != null && w >= 0 ? w : SINGLE_WEIGHT;
    }

    /** 이 도구의 묶음 목록. 도구 키가 없으면 기본 목록, 방어구는 항상 빈 목록. */
    public List<Bundle> bundles(Tool tool) {
        if (tool.isArmor()) return List.of();
        List<Bundle> own = bundles.get(tool.id());
        return own != null ? own : DEFAULT.bundles.getOrDefault(tool.id(), List.of());
    }

    /** 붙어 있는 능력 집합과 정확히 같은 묶음. */
    public Optional<Bundle> bundleOf(Tool tool, Set<Ability> abilities) {
        return SpecialEnchants.bundleOf(abilities, bundles(tool));
    }

    /**
     * 모든 도구 키를 명시하고 잘못된 값을 걸러 낸 사본. 음수 가중치는 기본값, 잘못된 묶음(모르는 능력, 그 도구에
     * 안 붙는 능력, 능력 2개 미만, 능력 중복, id 중복, 같은 능력 집합 중복)은 버린다. 걸러 낸 것마다 {@code errors}에 한 줄.
     */
    public PoolConfig sanitized(List<String> errors) {
        Map<String, Map<String, Integer>> newWeights = new LinkedHashMap<>();
        Map<String, List<Bundle>> newBundles = new LinkedHashMap<>();
        for (Tool tool : Tool.values()) {
            if (tool.isArmor()) continue;
            Map<String, Integer> byAbility = new LinkedHashMap<>();
            Map<String, Integer> given = weights.getOrDefault(tool.id(), Map.of());
            for (Ability a : SpecialEnchants.pool(tool)) {
                Integer w = given.get(a.id());
                if (w != null && w < 0) {
                    errors.add("enchant.json: pool.weights." + tool.id() + "." + a.id() + " " + w
                            + " is negative, using " + SINGLE_WEIGHT);
                    w = null;
                }
                byAbility.put(a.id(), w != null ? w : SINGLE_WEIGHT);
            }
            newWeights.put(tool.id(), byAbility);
            newBundles.put(tool.id(), sanitizedBundles(tool, bundles(tool), errors));
        }
        for (String key : bundles.keySet()) {
            if (!newBundles.containsKey(key)) {
                errors.add("enchant.json: pool.bundles." + key + " is not a tool that takes bundles, ignored");
            }
        }
        LevelReroll r = levelReroll;
        if (r.goldCost() < 0 || r.stoneCost() < 0) {
            errors.add("enchant.json: pool.levelReroll costs must not be negative, using defaults");
            r = new LevelReroll(r.enabled(), LevelReroll.DEFAULT.goldCost(), LevelReroll.DEFAULT.stoneCost());
        }
        return new PoolConfig(newWeights, newBundles, r);
    }

    private static List<Bundle> sanitizedBundles(Tool tool, List<Bundle> list, List<String> errors) {
        List<Bundle> out = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        Set<Set<Ability>> sets = new HashSet<>();
        for (Bundle b : list) {
            String where = "enchant.json: pool.bundles." + tool.id() + "." + b.id();
            String problem = problem(tool, b, ids, sets);
            if (problem != null) {
                errors.add(where + " " + problem + ", ignored");
                continue;
            }
            int weight = b.weight();
            if (weight < 0) {
                errors.add(where + " weight " + weight + " is negative, using 0");
                weight = 0;
            }
            ids.add(b.id());
            sets.add(b.abilitySet());
            out.add(new Bundle(b.id(), b.abilities(), weight, b.name() == null ? "" : b.name().strip()));
        }
        return List.copyOf(out);
    }

    /** 이 묶음을 버려야 하는 이유. 문제없으면 null. */
    static String problem(Tool tool, Bundle b, Set<String> seenIds, Set<Set<Ability>> seenSets) {
        if (b.id() == null || b.id().isBlank()) return "has no id";
        if (seenIds.contains(b.id())) return "repeats an id";
        if (b.abilities().size() < 2) return "needs at least 2 abilities";
        Set<Ability> set = EnumSet.noneOf(Ability.class);
        for (String id : b.abilities()) {
            Optional<Ability> a = Ability.byId(id);
            if (a.isEmpty()) return "names unknown ability " + id;
            if (!a.get().fits(tool)) return "names " + id + ", which does not go on a " + tool.id();
            if (!set.add(a.get())) return "lists " + id + " twice";
        }
        if (seenSets.contains(set)) return "has the same abilities as an earlier bundle";
        return null;
    }
}
