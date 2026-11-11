package org.gettserver.gettcore.upgrade;

import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;

/**
 * upgrade.json: 장비 등급 업그레이드 단계 목록. 한 단계 = {@code from} 아이템을 재료 {@code count}개(+골드)로
 * {@code to} 아이템으로 바꾼다. {@code from} 은 목록 안에서 한 번만 나오고, 단계를 따라가다 처음으로 돌아오면(순환) 안
 * 된다. 판정은 {@link UpgradeRules}, 적용은 {@code UpgradeService}.
 */
public record UpgradeConfig(List<Step> steps, List<TierCost> backpackTiers) {
    public static final String FILE = "upgrade.json";
    public static final int MAX_COUNT = 64 * 36;

    /**
     * Cost of one Traveler's Backpack tier step. Every backpack keeps its item id and carries the tier as a
     * component, so these are not {@link Step}s: entry n is the cost of tier n → n + 1 (leather → iron → gold →
     * diamond → netherite) for any backpack. An empty list switches backpack upgrading off.
     */
    public record TierCost(String material, int count, long gold) {
        public static final Codec<TierCost> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("material").forGetter(TierCost::material),
                Codec.INT.optionalFieldOf("count").forGetter(t -> Optional.of(t.count())),
                Codec.LONG.optionalFieldOf("gold").forGetter(t -> Optional.of(t.gold()))
        ).apply(i, (material, count, gold) -> new TierCost(material, count.orElse(1), gold.orElse(0L))));
    }

    /** Leather → iron 20 iron, → gold 20 gold, → diamond 10 diamonds, → netherite 4 netherite ingots. */
    public static final List<TierCost> BACKPACK_DEFAULT = List.of(
            new TierCost("minecraft:iron_ingot", 20, 0), new TierCost("minecraft:gold_ingot", 20, 0),
            new TierCost("minecraft:diamond", 10, 0), new TierCost("minecraft:netherite_ingot", 4, 0));
    /** Backpacks have five tiers, so four steps. */
    public static final int BACKPACK_STEPS = 4;

    /** Without the file only backpacks can be upgraded (the bundled default file carries the Epic Knights chains). */
    public static final UpgradeConfig DEFAULT = new UpgradeConfig(List.of());

    /** Steps with the default backpack costs (what a file without {@code backpackTiers} means). */
    public UpgradeConfig(List<Step> steps) {
        this(steps, BACKPACK_DEFAULT);
    }

    public record Step(String from, String to, String material, int count, long gold) {
        public static final Codec<Step> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.STRING.fieldOf("from").forGetter(Step::from),
                Codec.STRING.fieldOf("to").forGetter(Step::to),
                Codec.STRING.fieldOf("material").forGetter(Step::material),
                // Optional on read (count 1, gold 0) but always written, so a hand-edited file shows both.
                Codec.INT.optionalFieldOf("count").forGetter(s -> Optional.of(s.count())),
                Codec.LONG.optionalFieldOf("gold").forGetter(s -> Optional.of(s.gold()))
        ).apply(i, (from, to, material, count, gold) -> new Step(from, to, material, count.orElse(1),
                gold.orElse(0L))));
    }

    public static final Codec<UpgradeConfig> CODEC = RecordCodecBuilder.create(i -> i.group(
            Step.CODEC.listOf().optionalFieldOf("steps", List.of()).forGetter(UpgradeConfig::steps),
            TierCost.CODEC.listOf().optionalFieldOf("backpackTiers", BACKPACK_DEFAULT)
                    .forGetter(UpgradeConfig::backpackTiers)
    ).apply(i, UpgradeConfig::new));

    /** Server → client copy (the screen shows the next item and its cost). */
    public static final StreamCodec<ByteBuf, UpgradeConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 22).map(
            UpgradeConfig::fromJsonString, UpgradeConfig::toJsonString);

    public UpgradeConfig {
        steps = List.copyOf(steps);
        backpackTiers = List.copyOf(backpackTiers);
    }

    /**
     * The step for a backpack {@code item} at {@code tier} (0 = leather): same item on both sides, the cost of
     * that tier. Empty at the last tier or when backpack upgrading is off.
     */
    public Optional<Step> backpackStep(String item, int tier) {
        if (tier < 0 || tier >= backpackTiers.size()) return Optional.empty();
        TierCost c = backpackTiers.get(tier);
        return Optional.of(new Step(item, item, c.material(), c.count(), c.gold()));
    }

    /** Whether {@code step} came from {@link #backpackStep} (the item stays, only its tier goes up). */
    public static boolean isBackpackStep(Step step) {
        return step.from().equals(step.to());
    }

    public static String toJsonString(UpgradeConfig c) {
        return CODEC.encodeStart(JsonOps.INSTANCE, c).getOrThrow(IllegalStateException::new).toString();
    }

    public static UpgradeConfig fromJsonString(String s) {
        return CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(s)).getOrThrow(IllegalStateException::new);
    }

    /** The first step whose {@code from} is {@code item} (a sanitized config has at most one). */
    public Optional<Step> step(String item) {
        for (Step s : steps) {
            if (s.from().equals(item)) return Optional.of(s);
        }
        return Optional.empty();
    }

    /** {@code item} is the start or the result of some step (the screen accepts it, a last tier shows "최대 등급"). */
    public boolean involves(String item) {
        for (Step s : steps) {
            if (s.from().equals(item) || s.to().equals(item)) return true;
        }
        return false;
    }

    /**
     * Everything that makes a step unusable, one line each ("steps[n] ..."): an id that is not an item id, count
     * below 1 or above {@link #MAX_COUNT}, gold below 0, from = to, a {@code from} used by an earlier step, a step
     * that closes a cycle with the earlier ones.
     */
    public List<String> errors() {
        List<String> out = new ArrayList<>();
        check(out);
        checkBackpack(out);
        return out;
    }

    /** Drops each step {@link #errors()} would reject for its ids, duplicates or cycles; clamps count and gold. */
    public UpgradeConfig sanitized(List<String> warnings) {
        List<String> lines = new ArrayList<>();
        List<Step> kept = check(lines);
        List<TierCost> tiers = checkBackpack(lines);
        lines.forEach(l -> warnings.add(FILE + ": " + l));
        UpgradeConfig c = new UpgradeConfig(kept, tiers);
        return c.equals(this) ? this : c;
    }

    /** Reports every problem into {@code out} and returns the usable steps (count/gold clamped). */
    private List<Step> check(List<String> out) {
        List<Step> kept = new ArrayList<>();
        Map<String, String> next = new HashMap<>();
        for (int n = 0; n < steps.size(); n++) {
            Step s = steps.get(n);
            String p = "steps[" + n + "] " + s.from();
            boolean drop = false;
            for (String[] f : new String[][]{{"from", s.from()}, {"to", s.to()}, {"material", s.material()}}) {
                if (!isItemId(f[1])) {
                    out.add(p + ": " + f[0] + " '" + f[1] + "' is not an item id (skipped)");
                    drop = true;
                }
            }
            if (drop) continue;
            if (s.from().equals(s.to())) {
                out.add(p + ": from and to are the same item (skipped)");
                continue;
            }
            if (next.containsKey(s.from())) {
                out.add(p + ": another step already starts from this item (skipped)");
                continue;
            }
            if (UpgradeRules.reaches(next, s.to(), s.from())) {
                out.add(p + ": upgrading to " + s.to() + " leads back to " + s.from() + " (cycle, skipped)");
                continue;
            }
            int count = s.count();
            if (count < 1 || count > MAX_COUNT) {
                count = Math.max(1, Math.min(MAX_COUNT, count));
                out.add(p + ": count " + s.count() + " must be 1.." + MAX_COUNT + " (set to " + count + ")");
            }
            long gold = s.gold();
            if (gold < 0) {
                gold = 0;
                out.add(p + ": gold " + s.gold() + " must be >= 0 (set to 0)");
            }
            next.put(s.from(), s.to());
            kept.add(count == s.count() && gold == s.gold() ? s : new Step(s.from(), s.to(), s.material(), count, gold));
        }
        return kept;
    }

    /**
     * Backpack tier costs: at most {@link #BACKPACK_STEPS}; a material that is not an item id ends the list there
     * (a later tier could not be reached anyway); count and gold are clamped.
     */
    private List<TierCost> checkBackpack(List<String> out) {
        List<TierCost> kept = new ArrayList<>();
        for (int n = 0; n < backpackTiers.size(); n++) {
            TierCost t = backpackTiers.get(n);
            String p = "backpackTiers[" + n + "]";
            if (n >= BACKPACK_STEPS) {
                out.add(p + ": backpacks have only " + BACKPACK_STEPS + " tier steps (ignored)");
                break;
            }
            if (!isItemId(t.material())) {
                out.add(p + ": material '" + t.material() + "' is not an item id (this and later tiers skipped)");
                break;
            }
            int count = t.count();
            if (count < 1 || count > MAX_COUNT) {
                count = Math.max(1, Math.min(MAX_COUNT, count));
                out.add(p + ": count " + t.count() + " must be 1.." + MAX_COUNT + " (set to " + count + ")");
            }
            long gold = t.gold();
            if (gold < 0) {
                gold = 0;
                out.add(p + ": gold " + t.gold() + " must be >= 0 (set to 0)");
            }
            kept.add(count == t.count() && gold == t.gold() ? t : new TierCost(t.material(), count, gold));
        }
        return kept;
    }

    /** Every item id the steps mention (from, to, material). */
    public Set<String> itemIds() {
        Set<String> out = new HashSet<>();
        for (Step s : steps) {
            out.add(s.from());
            out.add(s.to());
            out.add(s.material());
        }
        for (TierCost t : backpackTiers) out.add(t.material());
        return out;
    }

    static boolean isItemId(String s) {
        return s != null && s.indexOf(':') > 0 && ResourceLocation.tryParse(s) != null;
    }
}
