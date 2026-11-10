package org.gettserver.gettcore.quest.client;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Optional;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import org.gettserver.gettcore.network.QuestSyncPayload;
import org.gettserver.gettcore.network.QuestSyncPayload.ObjState;
import org.gettserver.gettcore.network.QuestSyncPayload.QuestSummary;
import org.gettserver.gettcore.quest.QuestStatus.Phase;
import org.gettserver.gettcore.quest.client.QuestText.LogEntry;
import org.gettserver.gettcore.quest.client.QuestText.Status;
import org.junit.jupiter.api.Test;

class QuestTextTest {
    private static final Component LOG = Component.literal("원목");

    private static ObjState obj(String type, String target, String label, int cur, int req, boolean done) {
        return new ObjState(type, target, label, cur, req, done);
    }

    private static TranslatableContents tr(Component c) {
        return assertInstanceOf(TranslatableContents.class, c.getContents());
    }

    private static QuestSyncPayload.LineView sync(Phase phase, String questId, String npc, List<ObjState> objs,
                                                  List<String> done) {
        return new QuestSyncPayload.LineView("main", "메인 퀘스트", "", "준비 중", phase == Phase.LINE_DONE ? "" : questId,
                "1장. 시작", phase == Phase.LINE_DONE ? "" : "나무 모으기", phase, npc, objs, done,
                List.of(new QuestSummary("a", "1장. 시작", "첫 걸음"), new QuestSummary("b", "1장. 시작", "나무 모으기"),
                        new QuestSummary("c", "2장. 광산", "철 캐기")), false);
    }

    @Test
    void configuredLabelWins() {
        Component c = QuestText.objectiveLabel(obj("collect", "#minecraft:logs", "원목 모으기", 0, 16, false), LOG);
        assertEquals("원목 모으기", c.getString());
    }

    @Test
    void automaticLabels() {
        TranslatableContents collect = tr(QuestText.objectiveLabel(obj("collect", "#minecraft:logs", "", 0, 16, false),
                LOG));
        assertEquals("gettcore.quest.obj.collect", collect.getKey());
        assertSame(LOG, collect.getArgs()[0]);
        assertEquals(16, collect.getArgs()[1]);

        TranslatableContents kill = tr(QuestText.objectiveLabel(obj("kill", "minecraft:zombie", "", 0, 5, false),
                Component.literal("좀비")));
        assertEquals("gettcore.quest.obj.kill", kill.getKey());
        assertEquals(5, kill.getArgs()[1]);

        TranslatableContents adv = tr(QuestText.objectiveLabel(
                obj("advancement", "minecraft:story/smelt_iron", "", 0, 1, false), Component.literal("철 제련")));
        assertEquals("gettcore.quest.obj.advancement", adv.getKey());
        assertEquals("철 제련", ((Component) adv.getArgs()[0]).getString());

        TranslatableContents talk = tr(QuestText.objectiveLabel(obj("talk", "이장", "", 0, 1, false),
                Component.literal("이장")));
        assertEquals("gettcore.quest.obj.talk", talk.getKey());
        assertEquals("과", talk.getArgs()[1]);
    }

    @Test
    void particleFollowsFinalConsonant() {
        assertEquals("과", QuestText.waGwa("이장"));
        assertEquals("와", QuestText.waGwa("대장장이"));
        assertEquals("와(과)", QuestText.waGwa("Bob"));
        assertEquals("와(과)", QuestText.waGwa(""));
    }

    @Test
    void objectiveLineShowsProgressAndDone() {
        Component open = QuestText.objectiveLine(obj("collect", "x", "", 7, 16, false), LOG);
        assertEquals("• 원목 7/16", open.getString());
        assertEquals(ChatFormatting.WHITE.getColor(), open.getStyle().getColor().getValue());

        Component over = QuestText.objectiveLine(obj("collect", "x", "", 20, 16, true), LOG);
        assertEquals("✔ 원목 16/16", over.getString());
        assertEquals(ChatFormatting.GREEN.getColor(), over.getStyle().getColor().getValue());

        assertEquals("• 원목 0/16", QuestText.objectiveLine(obj("collect", "x", "", -3, 16, false), LOG).getString());
    }

    @Test
    void headerLine() {
        assertEquals("[1장] 첫 걸음", QuestText.headerLine("1장", "첫 걸음").getString());
        assertEquals("첫 걸음", QuestText.headerLine("", "첫 걸음").getString());
    }

    @Test
    void phaseLines() {
        TranslatableContents wait = tr(QuestText.phaseLine(Phase.WAIT_START, "이장", "").orElseThrow());
        assertEquals("gettcore.quest.hud.wait_start", wait.getKey());
        assertEquals("이장", wait.getArgs()[0]);
        assertEquals("gettcore.quest.hud.report",
                tr(QuestText.phaseLine(Phase.READY_TO_REPORT, "이장", "").orElseThrow()).getKey());
        assertEquals("gettcore.quest.hud.wait_start_any",
                tr(QuestText.phaseLine(Phase.WAIT_START, "", "").orElseThrow()).getKey());
        assertEquals("준비 중", QuestText.phaseLine(Phase.LINE_DONE, "", "준비 중").orElseThrow().getString());
        assertEquals("gettcore.quest.hud.line_done",
                tr(QuestText.phaseLine(Phase.LINE_DONE, "", " ").orElseThrow()).getKey());
        assertEquals(Optional.empty(), QuestText.phaseLine(Phase.ACTIVE, "", "x"));
    }

    @Test
    void hudLinesPerPhase() {
        List<ObjState> objs = List.of(obj("collect", "x", "원목", 7, 16, false), obj("kill", "y", "좀비", 5, 5, true));
        List<Component> active = QuestText.hudLines(sync(Phase.ACTIVE, "b", "", objs, List.of("a")),
                o -> Component.literal(o.label()));
        assertEquals(List.of("[1장. 시작] 나무 모으기", "• 원목 7/16", "✔ 좀비 5/5"),
                active.stream().map(Component::getString).toList());

        List<Component> wait = QuestText.hudLines(sync(Phase.WAIT_START, "b", "이장", objs, List.of("a")),
                o -> Component.literal(o.label()));
        assertEquals(2, wait.size());
        assertEquals("gettcore.quest.hud.wait_start", tr(wait.get(1)).getKey());

        List<Component> done = QuestText.hudLines(sync(Phase.LINE_DONE, "", "", List.of(), List.of("a", "b", "c")),
                o -> Component.literal(o.label()));
        assertEquals(List.of("메인 퀘스트", "준비 중"), done.stream().map(Component::getString).toList());

        assertTrue(QuestText.hudLines(new QuestSyncPayload.LineView("", "", "", "", "", "", "", Phase.LINE_DONE, "",
                List.of(), List.of(), List.of(), false), o -> LOG).isEmpty());
    }

    @Test
    void logEntriesGroupByChapter() {
        List<LogEntry> rows = QuestText.logEntries(sync(Phase.ACTIVE, "b", "", List.of(), List.of("a")));
        assertEquals(5, rows.size());
        assertTrue(rows.get(0).header());
        assertEquals("1장. 시작", rows.get(0).chapter());
        assertEquals(Status.DONE, rows.get(1).status());
        assertEquals(Status.CURRENT, rows.get(2).status());
        assertTrue(rows.get(3).header());
        assertEquals("2장. 광산", rows.get(3).chapter());
        assertEquals(Status.LOCKED, rows.get(4).status());
        assertEquals("c", rows.get(4).id());
    }

    @Test
    void toastSubtitleShowsGoldOnlyWhenPositive() {
        assertEquals("첫 걸음", QuestText.toastSubtitle("첫 걸음", 0, "골드").getString());
        assertEquals("첫 걸음  +1,200 골드", QuestText.toastSubtitle("첫 걸음", 1200, "골드").getString());
    }

    @Test
    void notifyQueueSpacesItems() {
        assertEquals(30, QuestToasts.SPACING_TICKS); // 1.5 s
        NotifyQueue<String> q = new NotifyQueue<>(QuestToasts.SPACING_TICKS, 3);
        assertNull(q.poll(0));
        q.offer("a");
        q.offer("b");
        q.offer("c");
        q.offer("d"); // over capacity: dropped
        assertEquals("a", q.poll(100));
        assertNull(q.poll(105));
        assertNull(q.poll(129));
        assertEquals("b", q.poll(130));
        assertNull(q.poll(159));
        assertEquals("c", q.poll(160));
        assertNull(q.poll(200));
        q.offer("e");
        assertEquals("e", q.poll(200));
        q.clear();
        q.offer("f");
        assertEquals("f", q.poll(201));
    }
}
