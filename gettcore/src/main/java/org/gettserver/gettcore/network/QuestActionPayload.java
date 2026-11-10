package org.gettserver.gettcore.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;

/**
 * Client to server. ACCEPT/REPORT answer an OFFER/REPORT {@link QuestDetailPayload}; the server re-checks the current
 * quest and that the player is still within 8 blocks of the NPC that showed it (valid for 60 s). VIEW asks for a
 * read-only {@link QuestDetailPayload} of any quest of line {@code lineId} (quest list screen). {@code choice} is the
 * picked reward index of a REPORT when the quest has reward choices; -1 when nothing was picked / not a report.
 */
public record QuestActionPayload(String lineId, String questId, Action action, int choice)
        implements CustomPacketPayload {
    public enum Action { ACCEPT, REPORT, VIEW }

    public static final Type<QuestActionPayload> TYPE = new Type<>(GettCore.id("quest_action"));
    public static final StreamCodec<FriendlyByteBuf, QuestActionPayload> STREAM_CODEC = StreamCodec.of(
            (buf, p) -> {
                buf.writeUtf(p.lineId(), 256);
                buf.writeUtf(p.questId(), 256);
                buf.writeEnum(p.action());
                buf.writeInt(p.choice());
            },
            buf -> new QuestActionPayload(buf.readUtf(256), buf.readUtf(256), buf.readEnum(Action.class),
                    buf.readInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
