package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.world.InteractionHand;
import org.gettserver.gettcore.GettCore;

/** C2S: [확정] on the obtain screen. Only the hand is sent; the server re-reads the item in that hand. */
public record ObtainConfirmPayload(InteractionHand hand) implements CustomPacketPayload {
    public static final Type<ObtainConfirmPayload> TYPE = new Type<>(GettCore.id("obtain_confirm"));
    public static final StreamCodec<ByteBuf, ObtainConfirmPayload> STREAM_CODEC = ByteBufCodecs.BOOL.map(
            main -> new ObtainConfirmPayload(main ? InteractionHand.MAIN_HAND : InteractionHand.OFF_HAND),
            p -> p.hand() == InteractionHand.MAIN_HAND);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
