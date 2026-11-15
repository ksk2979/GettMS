package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 플레이어 {@code entityId} 가 거미줄을 쓴 뒤 착지해 구른다(구르기 동작용). */
public record WebRollPayload(int entityId) implements CustomPacketPayload {
    public static final Type<WebRollPayload> TYPE = new Type<>(GettCore.id("web_roll"));
    public static final StreamCodec<ByteBuf, WebRollPayload> STREAM_CODEC =
            ByteBufCodecs.VAR_INT.map(WebRollPayload::new, WebRollPayload::entityId);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
