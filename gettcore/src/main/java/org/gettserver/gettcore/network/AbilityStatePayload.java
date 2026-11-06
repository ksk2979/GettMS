package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: 현재 특수 능력 켜짐/꺼짐 상태. */
public record AbilityStatePayload(boolean enabled) implements CustomPacketPayload {
    public static final Type<AbilityStatePayload> TYPE = new Type<>(GettCore.id("ability_state"));
    public static final StreamCodec<ByteBuf, AbilityStatePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, AbilityStatePayload::enabled,
            AbilityStatePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
