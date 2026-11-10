package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/** Client to server: show/hide a started sub line on the tracker HUD (saved per player; progress continues). */
public record QuestHidePayload(String lineId, boolean hidden) implements CustomPacketPayload {
    public static final Type<QuestHidePayload> TYPE = new Type<>(GettCore.id("quest_hide"));
    public static final StreamCodec<FriendlyByteBuf, QuestHidePayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.lineId(), 256);
                buf.writeBoolean(p.hidden());
            },
            buf -> new QuestHidePayload(buf.readUtf(256), buf.readBoolean()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
