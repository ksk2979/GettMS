package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * 슬링샷 상태. C2S 로는 내 상태를 알리고({@code entityId} 무시), S2C 로는 플레이어 {@code entityId} 의 상태를 주변에
 * 알린다. {@code state}: {@link #START} 양쪽 줄을 (ax, ay, az)·(bx, by, bz) 에 걸고 모으기 시작, {@link #CANCEL} 그만둠,
 * {@link #LAUNCH} 발사.
 */
public record WebSlingPayload(int entityId, byte state, double ax, double ay, double az, double bx, double by,
                              double bz) implements CustomPacketPayload {
    public static final byte START = 0;
    public static final byte CANCEL = 1;
    public static final byte LAUNCH = 2;

    public static final Type<WebSlingPayload> TYPE = new Type<>(GettCore.id("web_sling"));
    public static final StreamCodec<ByteBuf, WebSlingPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeInt(p.entityId);
                buf.writeByte(p.state);
                buf.writeDouble(p.ax);
                buf.writeDouble(p.ay);
                buf.writeDouble(p.az);
                buf.writeDouble(p.bx);
                buf.writeDouble(p.by);
                buf.writeDouble(p.bz);
            },
            buf -> new WebSlingPayload(buf.readInt(), buf.readByte(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), buf.readDouble(), buf.readDouble(), buf.readDouble()));

    public static WebSlingPayload end(int entityId, byte state) {
        return new WebSlingPayload(entityId, state, 0, 0, 0, 0, 0, 0);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
