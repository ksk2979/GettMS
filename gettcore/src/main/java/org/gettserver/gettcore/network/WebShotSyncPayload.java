package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 플레이어 {@code shooterId} 가 쏜 거미줄 탄이 (x, y, z) 까지 {@code flightTicks} 동안 날아간다(표시용). */
public record WebShotSyncPayload(int shooterId, boolean offhand, boolean bind, double x, double y, double z,
                                 int flightTicks) implements CustomPacketPayload {
    public static final Type<WebShotSyncPayload> TYPE = new Type<>(GettCore.id("web_shot_sync"));
    public static final StreamCodec<ByteBuf, WebShotSyncPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeInt(p.shooterId);
                buf.writeBoolean(p.offhand);
                buf.writeBoolean(p.bind);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeInt(p.flightTicks);
            },
            buf -> new WebShotSyncPayload(buf.readInt(), buf.readBoolean(), buf.readBoolean(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
