package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Client to server: the nickname typed after using a nickname ticket. The server checks the name and that a ticket is
 * still in hand, and takes one ticket only when the nickname was set.
 */
public record NicknameSetPayload(String name) implements CustomPacketPayload {
    public static final Type<NicknameSetPayload> TYPE = new Type<>(GettCore.id("nickname_set"));
    public static final StreamCodec<FriendlyByteBuf, NicknameSetPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeUtf(p.name(), 64),
            buf -> new NicknameSetPayload(buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
