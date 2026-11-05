package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import net.minecraft.resources.ResourceLocation;

public record ShopTab(String id, String name, ResourceLocation icon) {
    public static final Codec<ShopTab> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("id").forGetter(ShopTab::id),
            Codec.STRING.fieldOf("name").forGetter(ShopTab::name),
            ResourceLocation.CODEC.fieldOf("icon").forGetter(ShopTab::icon)
    ).apply(i, ShopTab::new));
}
