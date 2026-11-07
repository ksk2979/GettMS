package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class QuestEngineTest {
    private static final String CHIEF = "이장";

    // ---- builders ----

    private static QuestTrigger npc(String name) {
        return new QuestTrigger(QuestTrigger.Type.NPC, Optional.of(name));
    }

    private static Objective obj(Objective.Type type, String target, int count) {
        return new Objective(type, target, count, false, Optional.empty());
    }

    private static Quest quest(String id, QuestTrigger start, QuestTrigger complete, Objective... objectives) {
        return new Quest(id, "1장", id, "", start, complete, List.of(objectives), Rewards.EMPTY, "");
    }

    private static QuestLine line(Quest... quests) {
        return new QuestLine("main", "메인", QuestLine.DEFAULT_END_TEXT, List.of(quests));
    }

    /** Fake facts: item counts and advancements set by the test. */
    private static final class Facts implements PlayerFacts {
        final Map<String, Integer> items = new HashMap<>();
        final Set<String> advancements = new HashSet<>();
        boolean allAdvancements;
        int defaultItems;

        @Override
        public int countItem(String itemOrTag) {
            return items.getOrDefault(itemOrTag, defaultItems);
        }

        @Override
        public boolean hasAdvancement(String id) {
            return allAdvancements || advancements.contains(id);
        }
    }

    private static List<String> describe(List<QuestEvent> events) {
        List<String> out = new ArrayList<>();
        for (QuestEvent e : events) out.add(e.kind() + ":" + e.quest().id());
        return out;
    }

    // q1: chief starts, talk to chief, auto complete. q2: auto start, 16 logs. q3: auto, kill 3 zombies.
    private static final Quest Q1 = quest("q1", npc(CHIEF), QuestTrigger.AUTO,
            obj(Objective.Type.TALK, CHIEF, 1));
    private static final Quest Q2 = quest("q2", QuestTrigger.AUTO, QuestTrigger.AUTO,
            obj(Objective.Type.COLLECT, "#minecraft:logs", 16));
    private static final Quest Q3 = quest("q3", QuestTrigger.AUTO, QuestTrigger.AUTO,
            obj(Objective.Type.KILL, "minecraft:zombie", 3));

    // ---- tests ----

    @Test
    void freshPlayerWaitsForChief() {
        QuestLine line = line(Q1, Q2, Q3);
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        QuestStatus s = QuestEngine.status(line, p, f);
        assertEquals(QuestStatus.Phase.WAIT_START, s.phase());
        assertEquals("q1", s.quest().orElseThrow().id());
        assertTrue(QuestEngine.advance(line, p, f).isEmpty());
        assertTrue(p.accepted().isEmpty());
        assertTrue(p.completed().isEmpty());
    }

    @Test
    void interactWithChiefAcceptsAndChainsAutoQuests() {
        QuestLine line = line(Q1, Q2, Q3);
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        assertEquals(List.of("ACCEPTED:q1"), describe(QuestEngine.interact(line, p, f, CHIEF)));
        // interact does not record the talk objective; onTalk does.
        assertTrue(p.talked().isEmpty());
        assertEquals(QuestStatus.Phase.ACTIVE, QuestEngine.status(line, p, f).phase());
        assertTrue(QuestEngine.onTalk(line, p, CHIEF));
        assertFalse(QuestEngine.onTalk(line, p, CHIEF), "already talked");
        assertEquals(List.of("COMPLETED:q1", "ACCEPTED:q2"), describe(QuestEngine.advance(line, p, f)));
        assertEquals(Set.of("q1"), p.completed());
        assertEquals(Optional.of("q2"), p.accepted());
        assertTrue(p.talked().isEmpty(), "talk state reset after completion");
        QuestStatus s = QuestEngine.status(line, p, f);
        assertEquals(QuestStatus.Phase.ACTIVE, s.phase());
        assertEquals(new ObjectiveState(Q2.objectives().get(0), 0, 16, false), s.objectives().get(0));
    }

    @Test
    void veteranChainStopsAtNpcReport() throws Exception {
        String json;
        try (InputStream in = QuestEngineTest.class.getResourceAsStream("/gettcore_defaults/quests/main.json")) {
            assertNotNull(in, "bundled default main line");
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        QuestLine line = QuestLine.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        f.allAdvancements = true;
        f.defaultItems = 64;

        assertEquals(List.of("ACCEPTED:meet_chief"), describe(QuestEngine.interact(line, p, f, CHIEF)));
        assertTrue(QuestEngine.onTalk(line, p, CHIEF));
        List<String> events = describe(QuestEngine.advance(line, p, f));
        assertEquals(List.of(
                "COMPLETED:meet_chief",
                "ACCEPTED:gather_logs", "COMPLETED:gather_logs",
                "ACCEPTED:workbench_tools", "COMPLETED:workbench_tools",
                "ACCEPTED:stone_age", "COMPLETED:stone_age",
                "ACCEPTED:first_night"), events);
        QuestStatus s = QuestEngine.status(line, p, f);
        assertEquals("first_night", s.quest().orElseThrow().id());
        assertEquals(QuestStatus.Phase.READY_TO_REPORT, s.phase());
        assertEquals(4, p.completed().size());
        assertTrue(QuestEngine.advance(line, p, f).isEmpty(), "chain stays stopped at the npc report");
    }

    private static QuestLine reportLine() {
        Quest a = quest("a", QuestTrigger.AUTO, npc(CHIEF), obj(Objective.Type.ADVANCEMENT, "minecraft:story/x", 1));
        Quest b = quest("b", npc(CHIEF), QuestTrigger.AUTO, obj(Objective.Type.ADVANCEMENT, "minecraft:story/y", 1));
        return line(a, b);
    }

    @Test
    void reportAtWrongNpcDoesNothing() {
        QuestLine line = reportLine();
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        f.allAdvancements = true;
        assertEquals(List.of("ACCEPTED:a"), describe(QuestEngine.advance(line, p, f)));
        assertEquals(QuestStatus.Phase.READY_TO_REPORT, QuestEngine.status(line, p, f).phase());
        assertTrue(QuestEngine.interact(line, p, f, "대장장이").isEmpty());
        assertTrue(p.completed().isEmpty());
        assertEquals(Optional.of("a"), p.accepted());
    }

    @Test
    void reportAtRightNpcCompletesOnce() {
        QuestLine line = reportLine();
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        f.allAdvancements = true;
        QuestEngine.advance(line, p, f);
        // Reporting to the chief completes "a"; b's start is also the chief, but one interaction does one step.
        assertEquals(List.of("COMPLETED:a"), describe(QuestEngine.interact(line, p, f, CHIEF)));
        // Second click on the chief now accepts b (which auto-completes) — never completes "a" again.
        List<String> second = describe(QuestEngine.interact(line, p, f, CHIEF));
        assertEquals(List.of("ACCEPTED:b", "COMPLETED:b"), second);
        assertTrue(QuestEngine.interact(line, p, f, CHIEF).isEmpty());
        assertEquals(Set.of("a", "b"), p.completed());
        assertEquals(QuestStatus.Phase.LINE_DONE, QuestEngine.status(line, p, f).phase());
    }

    @Test
    void reportingTwiceWhileNotReadyDoesNotComplete() {
        QuestLine line = reportLine();
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        QuestEngine.advance(line, p, f);
        assertTrue(QuestEngine.interact(line, p, f, CHIEF).isEmpty(), "objectives not done");
        f.allAdvancements = true;
        assertEquals(List.of("COMPLETED:a"), describe(QuestEngine.interact(line, p, f, CHIEF)));
        assertEquals(1, p.completed().size());
    }

    @Test
    void reorderingKeepsCompletedAndNoDoubleReward() {
        QuestProgress p = new QuestProgress();
        p.completed().add("q1");
        p.completed().add("q2");
        Facts f = new Facts();
        f.defaultItems = 64;
        QuestLine reordered = line(Q2, Q1, Q3);
        assertEquals("q3", QuestEngine.current(reordered, p).orElseThrow().id());
        List<String> events = describe(QuestEngine.advance(reordered, p, f));
        assertEquals(List.of("ACCEPTED:q3"), events);
        assertEquals(Set.of("q1", "q2"), p.completed());
    }

    @Test
    void deletingAcceptedQuestResets() {
        QuestProgress p = new QuestProgress();
        p.completed().add("q1");
        p.setAccepted("q2");
        p.kills().put("minecraft:zombie", 2);
        p.talked().add(CHIEF);
        QuestLine withoutQ2 = line(Q1, Q3);
        assertEquals("q3", QuestEngine.current(withoutQ2, p).orElseThrow().id());
        Facts f = new Facts();
        assertEquals(QuestStatus.Phase.WAIT_START, QuestEngine.status(withoutQ2, p, f).phase(),
                "stale accepted is not counted for the new current quest");
        List<String> events = describe(QuestEngine.advance(withoutQ2, p, f));
        assertEquals(List.of("ACCEPTED:q3"), events);
        assertEquals(Optional.of("q3"), p.accepted());
        assertTrue(p.kills().isEmpty());
        assertTrue(p.talked().isEmpty());
    }

    @Test
    void deletingAcceptedQuestResetsBeforeKill() {
        QuestProgress p = new QuestProgress();
        p.completed().add("q1");
        p.setAccepted("q2");
        p.kills().put("minecraft:zombie", 2);
        QuestLine withoutQ2 = line(Q1, Q3);
        // Not accepted any more (stale) -> kill is not counted, stale counts cleared.
        assertFalse(QuestEngine.onKill(withoutQ2, p, "minecraft:zombie"::equals));
        assertTrue(p.accepted().isEmpty());
        assertTrue(p.kills().isEmpty());
    }

    @Test
    void switchingCompleteAutoToNpcWaitsForReport() {
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        f.allAdvancements = true;
        p.setAccepted("a");
        // Operator switched "a" from auto-complete to npc-complete: next judgment waits for the report.
        QuestLine npcComplete = reportLine();
        assertTrue(QuestEngine.advance(npcComplete, p, f).isEmpty());
        assertEquals(QuestStatus.Phase.READY_TO_REPORT, QuestEngine.status(npcComplete, p, f).phase());
        // And back to auto: completes on the next advance.
        Quest a = npcComplete.quests().get(0);
        QuestLine autoComplete = line(new Quest("a", a.chapter(), a.title(), a.description(), a.start(),
                QuestTrigger.AUTO, a.objectives(), a.rewards(), a.completeText()), npcComplete.quests().get(1));
        assertEquals(List.of("COMPLETED:a"), describe(QuestEngine.advance(autoComplete, p, f)));
    }

    @Test
    void switchingStartNpcToAutoAcceptsOnAdvance() {
        QuestProgress p = new QuestProgress();
        Facts f = new Facts();
        QuestLine npcStart = line(Q1, Q2);
        assertTrue(QuestEngine.advance(npcStart, p, f).isEmpty());
        Quest autoQ1 = new Quest("q1", Q1.chapter(), Q1.title(), Q1.description(), QuestTrigger.AUTO,
                Q1.complete(), Q1.objectives(), Q1.rewards(), Q1.completeText());
        assertEquals(List.of("ACCEPTED:q1"), describe(QuestEngine.advance(line(autoQ1, Q2), p, f)));
    }

    @Test
    void killCountsOnlyWhileAcceptedAndCapped() {
        QuestLine line = line(Q1, Q3);
        QuestProgress p = new QuestProgress();
        p.completed().add("q1");
        Facts f = new Facts();
        assertFalse(QuestEngine.onKill(line, p, "minecraft:zombie"::equals), "not accepted yet");
        assertTrue(p.kills().isEmpty());
        QuestEngine.advance(line, p, f); // auto-accept q3
        assertEquals(Optional.of("q3"), p.accepted());
        assertFalse(QuestEngine.onKill(line, p, "minecraft:skeleton"::equals), "wrong target");
        assertTrue(QuestEngine.onKill(line, p, "minecraft:zombie"::equals));
        assertTrue(QuestEngine.onKill(line, p, "minecraft:zombie"::equals));
        QuestStatus mid = QuestEngine.status(line, p, f);
        assertEquals(new ObjectiveState(Q3.objectives().get(0), 2, 3, false), mid.objectives().get(0));
        // onKill never completes the quest by itself, so we can over-kill (5 total) and check the cap.
        assertTrue(QuestEngine.onKill(line, p, "minecraft:zombie"::equals));
        assertFalse(QuestEngine.onKill(line, p, "minecraft:zombie"::equals), "already at count");
        assertFalse(QuestEngine.onKill(line, p, "minecraft:zombie"::equals), "already at count");
        assertEquals(3, p.kills().get("minecraft:zombie"));
        QuestStatus s = QuestEngine.status(line, p, f);
        assertEquals(3, s.objectives().get(0).current());
        assertEquals(3, s.objectives().get(0).required());
        assertTrue(s.objectives().get(0).done());
        assertEquals(QuestStatus.Phase.READY_TO_REPORT, s.phase());
    }

    @Test
    void lineDoneAfterLastQuest() {
        QuestLine line = line(Q1, Q2);
        QuestProgress p = new QuestProgress();
        p.completed().addAll(List.of("q1", "q2"));
        QuestStatus s = QuestEngine.status(line, p, new Facts());
        assertEquals(QuestStatus.Phase.LINE_DONE, s.phase());
        assertTrue(s.quest().isEmpty());
        assertTrue(s.objectives().isEmpty());
        assertTrue(QuestEngine.advance(line, p, new Facts()).isEmpty());
        assertTrue(QuestEngine.interact(line, p, new Facts(), CHIEF).isEmpty());
    }

    @Test
    void addingQuestAfterLineDoneContinues() {
        QuestProgress p = new QuestProgress();
        p.completed().addAll(List.of("q1", "q2"));
        assertEquals(List.of("ACCEPTED:q3"), describe(QuestEngine.advance(line(Q1, Q2, Q3), p, new Facts())));
    }

    @Test
    void zeroObjectiveQuestCompletesRightAfterAccept() {
        Quest empty = quest("e", npc(CHIEF), QuestTrigger.AUTO);
        QuestLine line = line(empty, Q2);
        QuestProgress p = new QuestProgress();
        assertEquals(List.of("ACCEPTED:e", "COMPLETED:e", "ACCEPTED:q2"),
                describe(QuestEngine.interact(line, p, new Facts(), CHIEF)));
    }

    @Test
    void talkOnlyRecordedWhileAcceptedAndForMatchingObjective() {
        QuestLine line = line(Q1, Q2);
        QuestProgress p = new QuestProgress();
        assertFalse(QuestEngine.onTalk(line, p, CHIEF), "not accepted");
        QuestEngine.interact(line, p, new Facts(), CHIEF);
        assertFalse(QuestEngine.onTalk(line, p, "대장장이"), "no talk objective for that npc");
        assertTrue(p.talked().isEmpty());
    }

    @Test
    void duplicateQuestIdsDoNotCrashOrRepeat() {
        QuestLine line = line(Q2, Q3, Q2);
        QuestProgress p = new QuestProgress();
        p.completed().addAll(List.of("q2", "q3"));
        assertTrue(QuestEngine.current(line, p).isEmpty());
        assertTrue(QuestEngine.advance(line, p, new Facts()).isEmpty());
        assertEquals(QuestStatus.Phase.LINE_DONE, QuestEngine.status(line, p, new Facts()).phase());
    }

    @Test
    void progressCodecRoundTrip() {
        QuestProgress p = new QuestProgress();
        p.completed().addAll(List.of("q1", "q2"));
        p.setAccepted("q3");
        p.kills().put("minecraft:zombie", 2);
        p.talked().add(CHIEF);
        JsonElement json = QuestProgress.CODEC.encodeStart(JsonOps.INSTANCE, p).getOrThrow();
        QuestProgress back = QuestProgress.CODEC.parse(JsonOps.INSTANCE, json).getOrThrow();
        assertEquals(p.completed(), back.completed());
        assertEquals(p.accepted(), back.accepted());
        assertEquals(p.kills(), back.kills());
        assertEquals(p.talked(), back.talked());
        // decoded collections stay mutable
        back.completed().add("q9");
        back.kills().put("x", 1);
        back.talked().add("y");

        QuestProgress empty = QuestProgress.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString("{}")).getOrThrow();
        assertTrue(empty.completed().isEmpty());
        assertTrue(empty.accepted().isEmpty());
        empty.completed().add("z");
    }
}
