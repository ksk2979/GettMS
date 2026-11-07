package org.gettserver.gettcore.quest;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

public record ItemReward(ResourceLocation item, int count) {
    public static final Codec<ItemReward> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("item").forGetter(ItemReward::item),
            Codec.intRange(1, Integer.MAX_VALUE).optionalFieldOf("count", 1).forGetter(ItemReward::count)
    ).apply(i, ItemReward::new));
}
