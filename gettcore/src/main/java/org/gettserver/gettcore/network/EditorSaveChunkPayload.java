package org.gettserver.gettcore.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Client to server: one chunk of a compressed editor save. */
public record EditorSaveChunkPayload(int sessionId, int index, int total, byte[] data)
        implements CustomPacketPayload {
    public static final Type<EditorSaveChunkPayload> TYPE = new Type<>(GettCore.id("editor_save_chunk"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditorSaveChunkPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.VAR_INT, EditorSaveChunkPayload::sessionId,
                    ByteBufCodecs.VAR_INT, EditorSaveChunkPayload::index,
                    ByteBufCodecs.VAR_INT, EditorSaveChunkPayload::total,
                    ByteBufCodecs.BYTE_ARRAY, EditorSaveChunkPayload::data,
                    EditorSaveChunkPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
