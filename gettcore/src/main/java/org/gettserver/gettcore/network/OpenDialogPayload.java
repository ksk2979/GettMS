package org.gettserver.gettcore.network;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import org.gettserver.gettcore.GettCore;

/** Server to client: show (or update) the dialog screen. An empty choiceLabels list means "close the dialog". */
public record OpenDialogPayload(int npcEntityId, String npcName, Optional<ResourceLocation> portrait, String text,
                                List<String> choiceLabels) implements CustomPacketPayload {
    public static final Type<OpenDialogPayload> TYPE = new Type<>(GettCore.id("open_dialog"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenDialogPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, OpenDialogPayload::npcEntityId,
            ByteBufCodecs.STRING_UTF8, OpenDialogPayload::npcName,
            ByteBufCodecs.optional(ResourceLocation.STREAM_CODEC), OpenDialogPayload::portrait,
            ByteBufCodecs.STRING_UTF8, OpenDialogPayload::text,
            ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenDialogPayload::choiceLabels,
            OpenDialogPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
