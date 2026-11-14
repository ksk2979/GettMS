package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: "부여" 버튼, 또는 {@code levelOnly}이면 "레벨만 다시 뽑기". 서버가 열린 메뉴의 슬롯만 보고 판정한다. */
public record EnchantApplyPayload(boolean levelOnly) implements CustomPacketPayload {
    public static final Type<EnchantApplyPayload> TYPE = new Type<>(GettCore.id("enchant_apply"));
    public static final StreamCodec<ByteBuf, EnchantApplyPayload> STREAM_CODEC =
            net.minecraft.network.codec.ByteBufCodecs.BOOL.map(EnchantApplyPayload::new, EnchantApplyPayload::levelOnly);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
