package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.shop.ShopMode;

/** Client to server: a buy/sell request. Prices are never sent; the server reads them from data. */
public record TradePayload(String shopId, ResourceLocation item, int count, ShopMode mode, boolean sellAll)
        implements CustomPacketPayload {
    public static final Type<TradePayload> TYPE = new Type<>(GettCore.id("trade"));
    public static final StreamCodec<ByteBuf, TradePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, TradePayload::shopId,
            ResourceLocation.STREAM_CODEC, TradePayload::item,
            ByteBufCodecs.VAR_INT, TradePayload::count,
            ByteBufCodecs.VAR_INT.map(i -> ShopMode.values()[Math.floorMod(i, ShopMode.values().length)], ShopMode::ordinal), TradePayload::mode,
            ByteBufCodecs.BOOL, TradePayload::sellAll,
            TradePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
