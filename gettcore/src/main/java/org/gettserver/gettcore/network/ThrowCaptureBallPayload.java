package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: 포획틀을 든 채 좌클릭 → 던지기. 데이터 없음 (손에 든 포획틀·쿨타임은 서버가 판정). */
public record ThrowCaptureBallPayload() implements CustomPacketPayload {
    public static final Type<ThrowCaptureBallPayload> TYPE = new Type<>(GettCore.id("throw_capture_ball"));
    public static final StreamCodec<ByteBuf, ThrowCaptureBallPayload> STREAM_CODEC =
            StreamCodec.unit(new ThrowCaptureBallPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
