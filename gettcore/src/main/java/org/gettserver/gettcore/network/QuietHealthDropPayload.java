package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * S2C: 바로 뒤따르는 체력 패킷의 감소는 피해가 아니라 최대 체력이 줄어 잘린 것(방어구 벗기)이니 피격 흔들림·하트 깜빡임 없이
 * 적용하라. 같은 틱에 체력 패킷보다 먼저 보내므로 클라이언트에서도 먼저 처리된다.
 */
public record QuietHealthDropPayload() implements CustomPacketPayload {
    public static final QuietHealthDropPayload INSTANCE = new QuietHealthDropPayload();
    public static final Type<QuietHealthDropPayload> TYPE = new Type<>(GettCore.id("quiet_health_drop"));
    public static final StreamCodec<ByteBuf, QuietHealthDropPayload> STREAM_CODEC = StreamCodec.unit(INSTANCE);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
