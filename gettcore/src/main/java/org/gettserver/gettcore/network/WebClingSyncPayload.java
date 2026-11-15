package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 플레이어 {@code entityId} 가 벽에 붙어 있는지(자세 표시용). */
public record WebClingSyncPayload(int entityId, boolean on) implements CustomPacketPayload {
    public static final Type<WebClingSyncPayload> TYPE = new Type<>(GettCore.id("web_cling_sync"));
    public static final StreamCodec<ByteBuf, WebClingSyncPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, WebClingSyncPayload::entityId,
            ByteBufCodecs.BOOL, WebClingSyncPayload::on,
            WebClingSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
