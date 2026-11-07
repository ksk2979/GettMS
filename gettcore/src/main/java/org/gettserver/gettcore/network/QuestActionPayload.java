package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Client to server. ACCEPT/REPORT answer an OFFER/REPORT {@link QuestDetailPayload}; the server re-checks the current
 * quest and that the player is still within 8 blocks of the NPC that showed it (valid for 60 s). VIEW asks for a
 * read-only {@link QuestDetailPayload} of any quest of the main line (quest list screen).
 */
public record QuestActionPayload(String questId, Action action) implements CustomPacketPayload {
    public enum Action { ACCEPT, REPORT, VIEW }

    public static final Type<QuestActionPayload> TYPE = new Type<>(GettCore.id("quest_action"));
    public static final StreamCodec<FriendlyByteBuf, QuestActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.questId(), 256);
                buf.writeEnum(p.action());
            },
            buf -> new QuestActionPayload(buf.readUtf(256), buf.readEnum(Action.class)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
