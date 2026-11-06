package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 부여 결과(화면 연출용). */
public record EnchantResultPayload(String abilityId, int level) implements CustomPacketPayload {
    public static final Type<EnchantResultPayload> TYPE = new Type<>(GettCore.id("enchant_result"));
    public static final StreamCodec<ByteBuf, EnchantResultPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, EnchantResultPayload::abilityId,
            ByteBufCodecs.VAR_INT, EnchantResultPayload::level,
            EnchantResultPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
