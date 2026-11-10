package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class QuestLinesTest {
    private static Quest quest(String id, Objective... objectives) {
        return new Quest(id, "1장", id, "", QuestTrigger.AUTO, QuestTrigger.AUTO, List.of(objectives),
                Rewards.EMPTY, "");
    }

    private static QuestLine main() {
        return new QuestLine("main", "메인", QuestLine.DEFAULT_END_TEXT, List.of(quest("q1"), quest("q2")));
    }

    private static QuestLine sub(String id, String requires) {
        return new QuestLine(id, id, QuestLine.DEFAULT_END_TEXT, List.of(quest("s1")),
                Optional.of("npc"), Optional.ofNullable(requires));
    }

    private static QuestProgress done(String... ids) {
        QuestProgress p = new QuestProgress();
        for (String i : ids) p.completed().add(i);
        return p;
    }

    @Test
    void startedWhenCompletedOrAccepted() {
        assertFalse(QuestLines.started(new QuestProgress()));
        assertTrue(QuestLines.started(done("q1")));
        QuestProgress p = new QuestProgress();
        p.setAccepted("q1");
        assertTrue(QuestLines.started(p));
    }

    @Test
    void requirementNoneIsMet() {
        assertTrue(QuestLines.requirementMet(sub("a", null), id -> new QuestProgress()));
    }

    @Test
    void requirementMetAfterQuestCompleted() {
        QuestLine l = sub("a", "main:q2");
        assertFalse(QuestLines.requirementMet(l, id -> done("q1")));
        assertTrue(QuestLines.requirementMet(l, id -> done("q1", "q2")));
    }

    @Test
    void badRequirementFormatIsUnmet() {
        assertFalse(QuestLines.requirementMet(sub("a", "q2"), id -> done("q2")));
    }

    @Test
    void judgedOrdersMainFirstThenStartedOrUnlockedSubs() {
        QuestLine m = main();
        QuestLine a = sub("a", null);
        QuestLine b = sub("b", "main:q2");
        Map<String, QuestProgress> prog = new HashMap<>();
        prog.put("a", done("s1"));
        List<QuestLine> out = QuestLines.judged(List.of(b, a, m),
                id -> prog.getOrDefault(id, new QuestProgress()));
        assertEquals(List.of("main", "a"), out.stream().map(QuestLine::id).toList());
    }

    @Test
    void applyKillCountsEveryLine() {
        Objective k = new Objective(Objective.Type.KILL, "minecraft:zombie", 3, false, Optional.empty());
        QuestLine m = new QuestLine("main", "m", QuestLine.DEFAULT_END_TEXT, List.of(quest("q1", k)));
        QuestLine s = new QuestLine("sub", "s", QuestLine.DEFAULT_END_TEXT, List.of(quest("s1", k)),
                Optional.of("npc"), Optional.empty());
        Map<String, QuestProgress> prog = new HashMap<>();
        for (String id : List.of("main", "sub")) {
            QuestProgress p = new QuestProgress();
            p.setAccepted(id.equals("main") ? "q1" : "s1");
            prog.put(id, p);
        }
        assertTrue(QuestLines.applyKill(List.of(m, s), prog::get, t -> t.equals("minecraft:zombie")));
        assertEquals(1, prog.get("main").kills().get("minecraft:zombie"));
        assertEquals(1, prog.get("sub").kills().get("minecraft:zombie"));
    }
}
