package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: the player clicked dex entry {@code index} (summon it, or recall it if it is the active pet). */
public record PetDexActionPayload(int index) implements CustomPacketPayload {
    public static final Type<PetDexActionPayload> TYPE = new Type<>(GettCore.id("pet_dex_action"));
    public static final StreamCodec<ByteBuf, PetDexActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, PetDexActionPayload::index,
            PetDexActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
