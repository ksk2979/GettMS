package org.gettserver.gettcore.drop;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * One extra kill drop: {@code item} (an item id or an item preset {@code preset:<name>}) with a {@code chance} in
 * percent and a count picked evenly from {@code min}..{@code max}. Used by mobs.json (per entity type, so also per
 * boss type) and by hunting variants. Rolled only when a player made the kill.
 */
public record Drop(ResourceLocation item, double chance, int min, int max) {
    public static final int MAX_COUNT = 640;
    public static final int MAX_DROPS = 32;

    public static final Codec<Drop> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("item").forGetter(Drop::item),
            Codec.DOUBLE.optionalFieldOf("chance", 100.0).forGetter(Drop::chance),
            Codec.INT.optionalFieldOf("min", 1).forGetter(Drop::min),
            Codec.INT.optionalFieldOf("max").forGetter(d -> Optional.of(d.max()))
    ).apply(i, (item, chance, min, max) -> new Drop(item, chance, min, max.orElse(min))));

    public static final Codec<List<Drop>> LIST_CODEC = CODEC.listOf();

    /** Chance into 0..100, counts into 1..{@link #MAX_COUNT} with min ≤ max; one warning per changed value. */
    Drop sanitized(String where, List<String> warnings) {
        double c = chance;
        if (Double.isNaN(c) || c < 0 || c > 100) {
            c = Double.isNaN(c) ? 0 : Math.max(0, Math.min(100, c));
            warnings.add(where + " drop " + item + " chance " + chance + " clamped to " + c);
        }
        int lo = Math.max(1, Math.min(MAX_COUNT, min));
        int hi = Math.max(lo, Math.min(MAX_COUNT, max));
        if (lo != min || hi != max) {
            warnings.add(where + " drop " + item + " count " + min + "~" + max + " changed to " + lo + "~" + hi);
        }
        return c == chance && lo == min && hi == max ? this : new Drop(item, c, lo, hi);
    }

    /** {@link #sanitized} for every entry; entries past {@link #MAX_DROPS} are dropped. Same list when nothing changed. */
    public static List<Drop> sanitizedList(String where, List<Drop> drops, List<String> warnings) {
        List<Drop> out = new ArrayList<>(drops.size());
        for (Drop d : drops) {
            if (out.size() >= MAX_DROPS) {
                warnings.add(where + " has more than " + MAX_DROPS + " drops, the rest are ignored");
                break;
            }
            out.add(d.sanitized(where, warnings));
        }
        return out.equals(drops) ? drops : List.copyOf(out);
    }

    /** How many to drop this time: 0 when the chance roll fails. */
    public int roll(RandomSource random) {
        if (chance <= 0 || random.nextDouble() * 100.0 >= chance) return 0;
        return max > min ? min + random.nextInt(max - min + 1) : min;
    }
}
