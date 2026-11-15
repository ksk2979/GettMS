package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * S2C: 플레이어 {@code entityId} 의 거미줄 상태(줄 그리기·팔 자세용). {@code active=false} 는 줄이 없어졌다는 뜻이고
 * 나머지 값은 쓰지 않는다. {@code flightTicks} 는 줄 끝이 (x, y, z) 에 닿기까지의 틱.
 */
public record WebSyncPayload(int entityId, boolean active, boolean hit, boolean offhand, double x, double y,
                             double z, int flightTicks) implements CustomPacketPayload {
    public static final Type<WebSyncPayload> TYPE = new Type<>(GettCore.id("web_sync"));
    public static final StreamCodec<ByteBuf, WebSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeInt(p.entityId);
                buf.writeBoolean(p.active);
                buf.writeBoolean(p.hit);
                buf.writeBoolean(p.offhand);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeInt(p.flightTicks);
            },
            buf -> new WebSyncPayload(buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readBoolean(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readInt()));

    public static WebSyncPayload gone(int entityId) {
        return new WebSyncPayload(entityId, false, false, false, 0, 0, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
