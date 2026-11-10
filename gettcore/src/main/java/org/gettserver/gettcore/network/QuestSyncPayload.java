package org.gettserver.gettcore.network;

import java.util.List;
import java.util.Optional;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.gettserver.gettcore.GettCore;
import org.gettserver.gettcore.quest.QuestStatus;

/**
 * Server to client: everything the tracking HUD and the quest list need, one {@link LineView} per line the player
 * follows. Order: the main line (when there is one), then the sub lines the player started, in first-started order.
 * Sub lines not started yet are not sent. Sent only when it changed. Empty list = nothing to show.
 */
public record QuestSyncPayload(List<LineView> lines) implements CustomPacketPayload {

    /**
     * One line. {@code giver} is the sub line's NPC ("" for the main line). {@code questId}/{@code chapter}/{@code title}
     * are empty when {@code phase} is LINE_DONE (show {@code endText}). {@code npc} is the NPC to visit: start NPC in
     * WAIT_START, complete NPC in READY_TO_REPORT, otherwise empty (also empty for auto triggers). {@code hidden}: the
     * player hid this sub line from the HUD (always false for the main line).
     */
    public record LineView(String lineId, String lineName, String giver, String endText, String questId,
                           String chapter, String title, QuestStatus.Phase phase, String npc,
                           List<ObjState> objectives, List<String> completedIds, List<QuestSummary> quests,
                           boolean hidden) {}

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
        return new QuestSyncPayload(List.of());
    }

    /** The main line's view (the one without a giver), if sent. */
    public Optional<LineView> main() {
        return lines.stream().filter(l -> l.giver().isEmpty()).findFirst();
    }

    private void write(FriendlyByteBuf buf) {
        buf.writeCollection(lines, QuestSyncPayload::writeLine);
    }

    private static QuestSyncPayload read(FriendlyByteBuf buf) {
        return new QuestSyncPayload(buf.readList(QuestSyncPayload::readLine));
    }

    private static void writeLine(FriendlyByteBuf buf, LineView l) {
        buf.writeUtf(l.lineId());
        buf.writeUtf(l.lineName());
        buf.writeUtf(l.giver());
        buf.writeUtf(l.endText());
        buf.writeUtf(l.questId());
        buf.writeUtf(l.chapter());
        buf.writeUtf(l.title());
        buf.writeEnum(l.phase());
        buf.writeUtf(l.npc());
        buf.writeCollection(l.objectives(), QuestSyncPayload::writeObj);
        buf.writeCollection(l.completedIds(), FriendlyByteBuf::writeUtf);
        buf.writeCollection(l.quests(), (b, q) -> {
            b.writeUtf(q.id());
            b.writeUtf(q.chapter());
            b.writeUtf(q.title());
        });
        buf.writeBoolean(l.hidden());
    }

    private static LineView readLine(FriendlyByteBuf buf) {
        return new LineView(buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(), buf.readUtf(),
                buf.readUtf(), buf.readEnum(QuestStatus.Phase.class), buf.readUtf(),
                buf.readList(QuestSyncPayload::readObj), buf.readList(FriendlyByteBuf::readUtf),
                buf.readList(b -> new QuestSummary(b.readUtf(), b.readUtf(), b.readUtf())), buf.readBoolean());
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
