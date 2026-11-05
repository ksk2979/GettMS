package org.gettserver.gettcore.network;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Client to server: chosen index, or -1 when the screen was closed. */
public record DialogChoicePayload(int choiceIndex) implements CustomPacketPayload {
    public static final Type<DialogChoicePayload> TYPE = new Type<>(GettCore.id("dialog_choice"));
    public static final StreamCodec<ByteBuf, DialogChoicePayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, DialogChoicePayload::choiceIndex,
            DialogChoicePayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
