package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** S2C: the receiving player (or their pet) just damaged entity {@code entityId}; shows its health bar. */
public record MobHitPayload(int entityId) implements CustomPacketPayload {
    public static final Type<MobHitPayload> TYPE = new Type<>(GettCore.id("mob_hit"));
    public static final StreamCodec<ByteBuf, MobHitPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, MobHitPayload::entityId,
            MobHitPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
