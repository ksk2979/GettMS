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
public record UpgradeConfig(List<Step> steps) {
    public static final String FILE = "upgrade.json";
    /** Without the file nothing can be upgraded (the bundled default file carries the Epic Knights chains). */
    public static final UpgradeConfig DEFAULT = new UpgradeConfig(List.of());
    public static final int MAX_COUNT = 64 * 36;

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
            Step.CODEC.listOf().optionalFieldOf("steps", List.of()).forGetter(UpgradeConfig::steps)
    ).apply(i, UpgradeConfig::new));

    /** Server → client copy (the screen shows the next item and its cost). */
    public static final StreamCodec<ByteBuf, UpgradeConfig> STREAM_CODEC = ByteBufCodecs.stringUtf8(1 << 22).map(
            UpgradeConfig::fromJsonString, UpgradeConfig::toJsonString);

    public UpgradeConfig {
        steps = List.copyOf(steps);
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
        return out;
    }

    /** Drops each step {@link #errors()} would reject for its ids, duplicates or cycles; clamps count and gold. */
    public UpgradeConfig sanitized(List<String> warnings) {
        List<String> lines = new ArrayList<>();
        List<Step> kept = check(lines);
        lines.forEach(l -> warnings.add(FILE + ": " + l));
        UpgradeConfig c = new UpgradeConfig(kept);
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

    /** Every item id the steps mention (from, to, material). */
    public Set<String> itemIds() {
        Set<String> out = new HashSet<>();
        for (Step s : steps) {
            out.add(s.from());
            out.add(s.to());
            out.add(s.material());
        }
        return out;
    }

    static boolean isItemId(String s) {
        return s != null && s.indexOf(':') > 0 && ResourceLocation.tryParse(s) != null;
    }
}
