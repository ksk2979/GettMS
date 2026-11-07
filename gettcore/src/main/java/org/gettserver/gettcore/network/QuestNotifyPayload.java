package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Server to client: a quest was accepted or completed (client shows a toast / centered title + sound, queued 0.5 s
 * apart when several arrive at once). {@code gold} is the gold reward for COMPLETED, 0 for ACCEPTED.
 */
public record QuestNotifyPayload(Kind kind, String title, long gold) implements CustomPacketPayload {
    public enum Kind { ACCEPTED, COMPLETED }

    public static final Type<QuestNotifyPayload> TYPE = new Type<>(GettCore.id("quest_notify"));
    public static final StreamCodec<FriendlyByteBuf, QuestNotifyPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeEnum(p.kind());
                buf.writeUtf(p.title());
                buf.writeVarLong(p.gold());
            },
            buf -> new QuestNotifyPayload(buf.readEnum(Kind.class), buf.readUtf(), buf.readVarLong()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
