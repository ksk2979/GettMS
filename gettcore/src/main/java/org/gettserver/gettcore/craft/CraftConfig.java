package org.gettserver.gettcore.craft;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * crafting.json: 제작대에서 만들 수 있는 것들. 제작법 하나 = 결과 아이템 {@code count}개를 재료들(과 골드)로 만든다.
 * 아이템 id 는 일반 아이템이나 프리셋({@code preset:<이름>}). 재료의 {@code minReinforce} 는 그 강화 단계 이상인
 * 장비만 재료로 쓴다는 뜻(0 = 상관없음). {@code category} 는 제작 창의 탭 이름. 판정은 {@link CraftRules}, 적용은
 * {@code CraftService}.
 */
public record CraftConfig(List<Recipe> recipes) {
    public static final String FILE = "crafting.json";
    public static final int MAX_COUNT = 64 * 36;
    public static final int MAX_INGREDIENTS = 12;
    public static final int MAX_REINFORCE = 99;

    public static final CraftConfig DEFAULT = new CraftConfig(List.of());

    public record Ingredient(String item, int count, int minReinforce) {
        public static final Codec<Ingredient> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("item").forGetter(Ingredient::item),
                Codec.INT.optionalFieldOf("count").forGetter(g -> Optional.of(g.count())),
                Codec.INT.optionalFieldOf("minReinforce").forGetter(g -> Optional.of(g.minReinforce()))
        ).apply(i, (item, count, min) -> new Ingredient(item, count.orElse(1), min.orElse(0))));
    }

    public record Recipe(String id, String category, String result, int count, long gold, List<Ingredient> ingredients) {
        public static final Codec<Recipe> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("id").forGetter(Recipe::id),
                Codec.STRING.optionalFieldOf("category", "").forGetter(Recipe::category),
                Codec.STRING.fieldOf("result").forGetter(Recipe::result),
                Codec.INT.optionalFieldOf("count").forGetter(r -> Optional.of(r.count())),
                Codec.LONG.optionalFieldOf("gold").forGetter(r -> Optional.of(r.gold())),
                Ingredient.CODEC.listOf().optionalFieldOf("ingredients", List.of()).forGetter(Recipe::ingredients)
        ).apply(i, (id, category, result, count, gold, ingredients) ->
                new Recipe(id, category, result, count.orElse(1), gold.orElse(0L), ingredients)));

        public Recipe {
            ingredients = List.copyOf(ingredients);
        }
    }

    public static final Codec<CraftConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Recipe.CODEC.listOf().optionalFieldOf("recipes", List.of()).forGetter(CraftConfig::recipes)
    ).apply(i, CraftConfig::new));

    /** Server → client copy (the crafting screen lists the recipes and checks the inventory itself). */
    public static final StreamCodec<ByteBuf, CraftConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 22).map(
            CraftConfig::fromJsonString, CraftConfig::toJsonString);

    public CraftConfig {
        recipes = List.copyOf(recipes);
    }

    public static String toJsonString(CraftConfig c) {
        return CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString();
    }

    public static CraftConfig fromJsonString(String s) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new);
    }

    public Optional<Recipe> recipe(String id) {
        for (Recipe r : recipes) if (r.id().equals(id)) return Optional.of(r);
        return Optional.empty();
    }

    /** Tab names in first-use order; a recipe without a category is under "". */
    public List<String> categories() {
        Set<String> out = new LinkedHashSet<>();
        for (Recipe r : recipes) out.add(r.category());
        return List.copyOf(out);
    }

    /**
     * Everything that makes a recipe unusable, one line each ("recipes[n] ..."): an empty or repeated id, an id that
     * is not an item id, a count below 1 or above {@link #MAX_COUNT}, gold below 0, no ingredients or more than
     * {@link #MAX_INGREDIENTS}, a reinforce level outside 0..{@link #MAX_REINFORCE}.
     */
    public List<String> errors() {
        List<String> out = new ArrayList<>();
        check(out);
        return out;
    }

    /** Drops each recipe {@link #errors()} would reject. */
    public CraftConfig sanitized(List<String> warnings) {
        List<String> lines = new ArrayList<>();
        List<Recipe> kept = check(lines);
        lines.forEach(l -> warnings.add(FILE + ": " + l));
        return new CraftConfig(kept);
    }

    private List<Recipe> check(List<String> out) {
        List<Recipe> kept = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int n = 0; n < recipes.size(); n++) {
            Recipe r = recipes.get(n);
            String at = "recipes[" + n + "] ";
            String problem = problem(r);
            if (problem == null && !ids.add(r.id())) problem = "id '" + r.id() + "' 가 앞에 이미 있습니다";
            if (problem != null) {
                out.add(at + problem);
                continue;
            }
            kept.add(r);
        }
        return kept;
    }

    private static String problem(Recipe r) {
        if (r.id() == null || r.id().isBlank()) return "id 가 비어 있습니다";
        if (!validItem(r.result())) return "결과 아이템 id 가 잘못됐습니다: " + r.result();
        if (r.count() < 1 || r.count() > MAX_COUNT) return "결과 개수는 1~" + MAX_COUNT;
        if (r.gold() < 0) return "골드는 0 이상";
        if (r.ingredients().isEmpty()) return "재료가 없습니다";
        if (r.ingredients().size() > MAX_INGREDIENTS) return "재료는 " + MAX_INGREDIENTS + "가지까지";
        for (Ingredient g : r.ingredients()) {
            if (!validItem(g.item())) return "재료 아이템 id 가 잘못됐습니다: " + g.item();
            if (g.count() < 1 || g.count() > MAX_COUNT) return "재료 개수는 1~" + MAX_COUNT;
            if (g.minReinforce() < 0 || g.minReinforce() > MAX_REINFORCE) return "강화 단계는 0~" + MAX_REINFORCE;
        }
        return null;
    }

    private static boolean validItem(String id) {
        return id != null && !id.isBlank() && ResourceLocation.tryParse(id) != null;
    }
}
