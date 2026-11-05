package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

public record BalanceSyncPayload(long balance) implements CustomPacketPayload {
    public static final Type<BalanceSyncPayload> TYPE = new Type<>(GettCore.id("balance_sync"));
    public static final StreamCodec<ByteBuf, BalanceSyncPayload> STREAM_CODEC =
            StreamCodec.composite(ByteBufCodecs.VAR_LONG, BalanceSyncPayload::balance, BalanceSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
