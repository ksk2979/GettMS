package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.GettCore;

/** Client to server: an operator shop edit. The server re-checks permission and validates every field. */
public record ShopEditPayload(String shopId, Action action, ResourceLocation item, String tab, long buyPrice,
                              long sellPrice, boolean canBuy, boolean canSell) implements CustomPacketPayload {
    public enum Action { SET_PRICE, ADD, REMOVE }

    public static final Type<ShopEditPayload> TYPE = new Type<>(GettCore.id("shop_edit"));
    private static final StreamCodec<ByteBuf, Action> ACTION_CODEC =
            ByteBufCodecs.VAR_INT.map(i -> Action.values()[Math.floorMod(i, Action.values().length)], Action::ordinal);

    // composite() tops out at 6 fields, so encode/decode by hand.
    public static final StreamCodec<ByteBuf, ShopEditPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, p.shopId());
                ACTION_CODEC.encode(buf, p.action());
                ResourceLocation.STREAM_CODEC.encode(buf, p.item());
                ByteBufCodecs.STRING_UTF8.encode(buf, p.tab());
                ByteBufCodecs.VAR_LONG.encode(buf, p.buyPrice());
                ByteBufCodecs.VAR_LONG.encode(buf, p.sellPrice());
                buf.writeBoolean(p.canBuy());
                buf.writeBoolean(p.canSell());
            },
            buf -> new ShopEditPayload(
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ACTION_CODEC.decode(buf),
                    ResourceLocation.STREAM_CODEC.decode(buf),
                    ByteBufCodecs.STRING_UTF8.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    ByteBufCodecs.VAR_LONG.decode(buf),
                    buf.readBoolean(),
                    buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
