package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: 붙어 있는(또는 날아가는) 거미줄을 뗀다. */
public record WebReleasePayload() implements CustomPacketPayload {
    public static final Type<WebReleasePayload> TYPE = new Type<>(GettCore.id("web_release"));
    public static final StreamCodec<ByteBuf, WebReleasePayload> STREAM_CODEC = StreamCodec.unit(new WebReleasePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
