package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.pet.PetKey;

/**
 * S2C: open the obtain screen for a captured mob. {@code percent} is the obtain chance shown in the hint;
 * {@code mainHand} is the hand holding the item, echoed back in {@link ObtainConfirmPayload}.
 */
public record OpenObtainPayload(PetKey key, boolean boss, int percent, ResourceLocation wildEntity, boolean mainHand)
        implements CustomPacketPayload {
    public static final Type<OpenObtainPayload> TYPE = new Type<>(GettCore.id("open_obtain"));
    public static final StreamCodec<ByteBuf, OpenObtainPayload> STREAM_CODEC = StreamCodec.composite(
            PetKey.STREAM_CODEC, OpenObtainPayload::key,
            ByteBufCodecs.BOOL, OpenObtainPayload::boss,
            ByteBufCodecs.VAR_INT, OpenObtainPayload::percent,
            ResourceLocation.STREAM_CODEC, OpenObtainPayload::wildEntity,
            ByteBufCodecs.BOOL, OpenObtainPayload::mainHand,
            OpenObtainPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
