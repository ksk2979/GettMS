package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** 귀환석 시전 시작/종료를 주변 클라이언트에 알린다(마법진 렌더용). */
public record ReturnCastPayload(int entityId, boolean active) implements CustomPacketPayload {
    public static final Type<ReturnCastPayload> TYPE = new Type<>(GettCore.id("return_cast"));
    public static final StreamCodec<ByteBuf, ReturnCastPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, ReturnCastPayload::entityId,
            ByteBufCodecs.BOOL, ReturnCastPayload::active,
            ReturnCastPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
