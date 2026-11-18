package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Server to client: open the crafting screen (a craft bench was used, or an NPC dialogue chose it). */
public record OpenCraftPayload() implements CustomPacketPayload {
    public static final Type<OpenCraftPayload> TYPE = new Type<>(GettCore.id("open_craft"));
    public static final StreamCodec<FriendlyByteBuf, OpenCraftPayload> STREAM_CODEC =
            StreamCodec.unit(new OpenCraftPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
