package org.gettserver.gettcore.data;

import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.util.Optional;
import net.minecraft.resources.ResourceLocation;

public record ShopEntry(ResourceLocation item, String tab, long buyPrice, long sellPrice, boolean canBuy,
                        boolean canSell, long currentBuyPrice, long currentSellPrice) {
    static final Codec<Long> PRICE = Codec.LONG.validate(v ->
            v >= 0 ? DataResult.success(v) : DataResult.error(() -> "price must be >= 0: " + v));

    public static final Codec<ShopEntry> CODEC = RecordCodecBuilder.create(i -> i.group(
            ResourceLocation.CODEC.fieldOf("item").forGetter(ShopEntry::item),
            Codec.STRING.fieldOf("tab").forGetter(ShopEntry::tab),
            PRICE.fieldOf("buyPrice").forGetter(ShopEntry::buyPrice),
            PRICE.fieldOf("sellPrice").forGetter(ShopEntry::sellPrice),
            Codec.BOOL.optionalFieldOf("canBuy", false).forGetter(ShopEntry::canBuy),
            Codec.BOOL.optionalFieldOf("canSell", false).forGetter(ShopEntry::canSell),
            PRICE.optionalFieldOf("currentBuyPrice").forGetter(e -> Optional.of(e.currentBuyPrice())),
            PRICE.optionalFieldOf("currentSellPrice").forGetter(e -> Optional.of(e.currentSellPrice()))
    ).apply(i, (item, tab, buy, sell, cb, cs, curBuy, curSell) ->
            new ShopEntry(item, tab, buy, sell, cb, cs, curBuy.orElse(buy), curSell.orElse(sell))));

    public ShopEntry withPrices(long buy, long sell) {
        return new ShopEntry(item, tab, buy, sell, canBuy, canSell, buy, sell);
    }
}
