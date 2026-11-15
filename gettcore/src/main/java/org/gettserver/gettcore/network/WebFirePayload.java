package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * C2S: 거미줄 발사. {@code hit} 이면 {@code block} 의 겉면 위 점 (x, y, z) 에 붙이려는 것이고, 아니면 허공으로 쏜
 * 것(연출만)이다. 서버가 사거리와 블록을 다시 확인한다.
 */
public record WebFirePayload(boolean hit, boolean offhand, double x, double y, double z, BlockPos block)
        implements CustomPacketPayload {
    public static final Type<WebFirePayload> TYPE = new Type<>(GettCore.id("web_fire"));
    public static final StreamCodec<ByteBuf, WebFirePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeBoolean(p.hit);
                buf.writeBoolean(p.offhand);
                buf.writeDouble(p.x);
                buf.writeDouble(p.y);
                buf.writeDouble(p.z);
                buf.writeLong(p.block.asLong());
            },
            buf -> new WebFirePayload(buf.readBoolean(), buf.readBoolean(), buf.readDouble(), buf.readDouble(),
                    buf.readDouble(), BlockPos.of(buf.readLong())));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
