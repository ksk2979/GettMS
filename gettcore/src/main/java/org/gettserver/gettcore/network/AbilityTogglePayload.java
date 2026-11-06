package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: 특수 능력 켜기/끄기 키. 데이터 없음. */
public record AbilityTogglePayload() implements CustomPacketPayload {
    public static final Type<AbilityTogglePayload> TYPE = new Type<>(GettCore.id("ability_toggle"));
    public static final StreamCodec<ByteBuf, AbilityTogglePayload> STREAM_CODEC =
            StreamCodec.unit(new AbilityTogglePayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
