package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.List;

/** Quest rewards: gold (via Ledger), items, experience levels. */
public record Rewards(long gold, List<ItemReward> items, int xp) {
    public static final Rewards EMPTY = new Rewards(0, List.of(), 0);

    public static final Codec<Rewards> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.LONG.optionalFieldOf("gold", 0L).forGetter(Rewards::gold),
            ItemReward.CODEC.listOf().optionalFieldOf("items", List.of()).forGetter(Rewards::items),
            Codec.INT.optionalFieldOf("xp", 0).forGetter(Rewards::xp)
    ).apply(i, Rewards::new));
}
