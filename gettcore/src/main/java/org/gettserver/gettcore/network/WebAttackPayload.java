package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: 양손 발사기 공격. {@code bind=false} 거미줄 쏘기, {@code true} 거미줄 묶기. {@code offhand} 는 쏘는 손(표시용). */
public record WebAttackPayload(boolean bind, boolean offhand) implements CustomPacketPayload {
    public static final Type<WebAttackPayload> TYPE = new Type<>(GettCore.id("web_attack"));
    public static final StreamCodec<ByteBuf, WebAttackPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, WebAttackPayload::bind,
            ByteBufCodecs.BOOL, WebAttackPayload::offhand,
            WebAttackPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
