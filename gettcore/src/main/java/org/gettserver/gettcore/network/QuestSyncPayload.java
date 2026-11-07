package org.gettserver.gettcore.network;

import java.util.List;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.quest.QuestStatus;

/**
 * Server to client: everything the tracking HUD and the quest list need for the main line. Sent only when it changed.
 * An empty {@code lineId} means there is no main line (hide the HUD). {@code questId}/{@code chapter}/{@code title} are
 * empty when {@code phase} is LINE_DONE (show {@code endText}). {@code npc} is the NPC to visit: start NPC in
 * WAIT_START, complete NPC in READY_TO_REPORT, otherwise empty (also empty for auto triggers).
 */
public record QuestSyncPayload(String lineId, String lineName, String endText, String questId, String chapter,
                               String title, QuestStatus.Phase phase, String npc, List<ObjState> objectives,
                               List<String> completedIds, List<QuestSummary> quests) implements CustomPacketPayload {

    /**
     * One objective: {@code type} is collect|kill|advancement|talk, {@code target} the item/entity id or #tag,
     * advancement id or NPC name; {@code label} may be empty (client builds one, e.g. "원목 16개 모으기").
     */
    public record ObjState(String type, String target, String label, int current, int required, boolean done) {}

    /** Line order entry for the quest list screen. */
    public record QuestSummary(String id, String chapter, String title) {}

    public static final Type<QuestSyncPayload> TYPE = new Type<>(GettCore.id("quest_sync"));
    public static final StreamCodec<FriendlyByteBuf, QuestSyncPayload> STREAM_CODEC =
            StreamCodec.of((buf, p) -> p.write(buf), QuestSyncPayload::read);

    public static QuestSyncPayload empty() {
        return new QuestSyncPayload("", "", "", "", "", "", QuestStatus.Phase.LINE_DONE, "", List.of(), List.of(),
                List.of());
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeUtf(lineId);
        buf.writeUtf(lineName);
        buf.writeUtf(endText);
        buf.writeUtf(questId);
        buf.writeUtf(chapter);
        buf.writeUtf(title);
        buf.writeEnum(phase);
        buf.writeUtf(npc);
        buf.writeCollection(objectives, QuestSyncPayload::writeObj);
        buf.writeCollection(completedIds, FriendlyByteBuf::writeUtf);
        buf.writeCollection(quests, (b, q) -> {
            b.writeUtf(q.id());
            b.writeUtf(q.chapter());
            b.writeUtf(q.title());
        });
    }

    private static QuestSyncPayload read(FriendlyByteBuf buf) {
        return new QuestSyncPayload(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readEnum(QuestStatus.Phase.class), buf.readUtf(),
                buf.readList(QuestSyncPayload::readObj), buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(b -> new QuestSummary(b.readUtf(), b.readUtf(), b.readUtf())));
    }

    static void writeObj(FriendlyByteBuf buf, ObjState o) {
        buf.writeUtf(o.type());
        buf.writeUtf(o.target());
        buf.writeUtf(o.label());
        buf.writeVarInt(o.current());
        buf.writeVarInt(o.required());
        buf.writeBoolean(o.done());
    }

    static ObjState readObj(FriendlyByteBuf buf) {
        return new ObjState(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readVarInt(), buf.readVarInt(),
                buf.readBoolean());
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
