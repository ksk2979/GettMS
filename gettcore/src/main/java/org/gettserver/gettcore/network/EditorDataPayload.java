package org.gettserver.gettcore.network;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Server to client: one chunk of an editor snapshot or of a save result. */
public record EditorDataPayload(Kind kind, int sessionId, int index, int total, byte[] data)
        implements CustomPacketPayload {
    public enum Kind { SNAPSHOT, RESULT }

    public static final Type<EditorDataPayload> TYPE = new Type<>(GettCore.id("editor_data"));
    public static final StreamCodec<RegistryFriendlyByteBuf, EditorDataPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.idMapper(i -> Kind.values()[i], Kind::ordinal), EditorDataPayload::kind,
            ByteBufCodecs.VAR_INT, EditorDataPayload::sessionId,
            ByteBufCodecs.VAR_INT, EditorDataPayload::index,
            ByteBufCodecs.VAR_INT, EditorDataPayload::total,
            ByteBufCodecs.BYTE_ARRAY, EditorDataPayload::data,
            EditorDataPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
