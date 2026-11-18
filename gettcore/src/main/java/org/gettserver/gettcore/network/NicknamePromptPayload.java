package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Server to client, on login: this player has no nickname yet, show the "choose your nickname" box. */
public record NicknamePromptPayload() implements CustomPacketPayload {
    public static final Type<NicknamePromptPayload> TYPE = new Type<>(GettCore.id("nickname_prompt"));
    public static final StreamCodec<FriendlyByteBuf, NicknamePromptPayload> STREAM_CODEC =
            StreamCodec.unit(new NicknamePromptPayload());

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
