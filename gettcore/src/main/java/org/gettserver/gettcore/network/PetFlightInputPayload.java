package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** C2S: the flying-mount rider's climb (jump key) / descend (sprint key) state, sent when it changes. */
public record PetFlightInputPayload(boolean up, boolean down) implements CustomPacketPayload {
    public static final Type<PetFlightInputPayload> TYPE = new Type<>(GettCore.id("pet_flight_input"));
    public static final StreamCodec<ByteBuf, PetFlightInputPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.BOOL, PetFlightInputPayload::up,
            ByteBufCodecs.BOOL, PetFlightInputPayload::down,
            PetFlightInputPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
