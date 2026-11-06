package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: "부여" 버튼. 데이터 없음 — 서버가 열린 메뉴의 슬롯만 보고 판정한다. */
public record EnchantApplyPayload() implements CustomPacketPayload {
    public static final Type<EnchantApplyPayload> TYPE = new Type<>(GettCore.id("enchant_apply"));
    public static final StreamCodec<ByteBuf, EnchantApplyPayload> STREAM_CODEC =
            StreamCodec.unit(new EnchantApplyPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
