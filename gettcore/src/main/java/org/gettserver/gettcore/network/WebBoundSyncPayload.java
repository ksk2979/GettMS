package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 엔티티 {@code entityId} 가 앞으로 {@code ticks} 틱 동안 거미줄에 묶여 있다(0 = 풀림). 표시용. */
public record WebBoundSyncPayload(int entityId, int ticks) implements CustomPacketPayload {
    public static final Type<WebBoundSyncPayload> TYPE = new Type<>(GettCore.id("web_bound_sync"));
    public static final StreamCodec<ByteBuf, WebBoundSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WebBoundSyncPayload::entityId,
            ByteBufCodecs.VAR_INT, WebBoundSyncPayload::ticks,
            WebBoundSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
