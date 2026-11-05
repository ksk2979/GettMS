package org.gettserver.gettcore.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.data.ShopDefinition;

/** Server to client: a whole shop definition, sent on open and after edits. */
public record ShopSyncPayload(String shopId, ShopDefinition definition, boolean dynamicsEnabled)
        implements CustomPacketPayload {
    public static final Type<ShopSyncPayload> TYPE = new Type<>(GettCore.id("shop_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ShopSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, ShopSyncPayload::shopId,
            ByteBufCodecs.fromCodecWithRegistries(ShopDefinition.CODEC), ShopSyncPayload::definition,
            ByteBufCodecs.BOOL, ShopSyncPayload::dynamicsEnabled,
            ShopSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
