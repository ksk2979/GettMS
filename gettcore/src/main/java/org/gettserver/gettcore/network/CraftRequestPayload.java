package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Client to server: make this recipe once. The server checks the ingredients, the gold and the distance again. */
public record CraftRequestPayload(String recipeId) implements CustomPacketPayload {
    public static final Type<CraftRequestPayload> TYPE = new Type<>(GettCore.id("craft_request"));
    public static final StreamCodec<FriendlyByteBuf, CraftRequestPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> buf.writeUtf(p.recipeId(), 256),
            buf -> new CraftRequestPayload(buf.readUtf(256)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
