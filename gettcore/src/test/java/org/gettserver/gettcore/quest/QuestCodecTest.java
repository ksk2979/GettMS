package org.gettserver.gettcore.quest;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;
import java.util.ArrayList;
import java.util.List;
import org.gettserver.gettcore.data.RegistryChecks;
import org.junit.jupiter.api.Test;

class QuestCodecTest {
    private static final String SPEC = """
            {
              "id": "main",
              "name": "메인 퀘스트",
              "endText": "다음 메인 퀘스트를 준비 중입니다",
              "quests": [
                {
                  "id": "first_steps",
                  "chapter": "1장. 시작",
                  "title": "첫 걸음",
                  "description": "어서 오게, 여행자! ... ",
                  "start": { "type": "npc", "npc": "이장" },
                  "complete": { "type": "auto" },
                  "objectives": [
                    { "type": "collect", "item": "#minecraft:logs", "count": 16, "consume": false, "label": "원목 모으기" }
                  ],
                  "rewards": { "gold": 200, "items": [ { "item": "gettcore:enchant_stone", "count": 1 } ] },
                  "completeText": "잘했네! 이제 작업대를 만들어 보게."
                }
              ]
            }""";

    private static QuestLine parse(String json) {
        return QuestLine.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
    }

    @Test
    void roundTripsSpecExample() {
        QuestLine line = parse(SPEC);
        Quest q = line.quests().get(0);
        Objective o = q.objectives().get(0);
        assertEquals(Objective.Type.COLLECT, o.type());
        assertEquals("#minecraft:logs", o.target());
        assertTrue(o.isTag());
        assertEquals(16, o.count());
        assertEquals(200, q.rewards().gold());
        assertEquals(QuestTrigger.Type.NPC, q.start().type());
        assertEquals("이장", q.start().npc().orElseThrow());
        assertEquals(QuestTrigger.Type.AUTO, q.complete().type());
        JsonElement out = QuestLine.CODEC.encodeStart(JsonOps.INSTANCE, line).getOrThrow();
        assertEquals(line, QuestLine.CODEC.parse(JsonOps.INSTANCE, out).getOrThrow());
        assertEquals("#minecraft:logs",
                out.getAsJsonObject().getAsJsonArray("quests").get(0).getAsJsonObject()
                        .getAsJsonArray("objectives").get(0).getAsJsonObject().get("item").getAsString());
    }

    @Test
    void defaultsAndPerTypeFields() {
        QuestLine line = parse("""
                {"id":"x","name":"X","quests":[{"id":"q","title":"T","objectives":[
                  {"type":"kill","entity":"minecraft:zombie","count":3},
                  {"type":"advancement","id":"minecraft:story/mine_diamond"},
                  {"type":"talk","npc":"이장"}]}]}""");
        assertEquals("다음 메인 퀘스트를 준비 중입니다", line.endText());
        Quest q = line.quests().get(0);
        assertEquals(QuestTrigger.Type.AUTO, q.start().type());
        assertEquals("", q.chapter());
        assertEquals(0, q.rewards().gold());
        assertEquals(Objective.Type.KILL, q.objectives().get(0).type());
        assertEquals("minecraft:zombie", q.objectives().get(0).target());
        assertEquals(1, q.objectives().get(1).count());
        assertFalse(q.objectives().get(1).consume());
        assertEquals("이장", q.objectives().get(2).target());
        assertFalse(QuestLine.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(
                "{\"id\":\"x\",\"name\":\"X\",\"quests\":[{\"id\":\"q\",\"title\":\"T\",\"objectives\":[{\"type\":\"kill\"}]}]}"))
                .result().isPresent());
    }

    @Test
    void pruneDropsMissingAndEmptiedQuests() {
        QuestLine line = parse("""
                {"id":"x","name":"X","quests":[
                 {"id":"a","title":"A","objectives":[{"type":"kill","entity":"othermod:beast"}]},
                 {"id":"b","title":"B","objectives":[{"type":"collect","item":"#minecraft:logs","count":4}],
                  "rewards":{"gold":5,"items":[{"item":"othermod:gem","count":1},{"item":"minecraft:bread","count":2}]}},
                 {"id":"c","title":"C","objectives":[]}]}""");
        RegistryChecks checks = new RegistryChecks(rl -> rl.getNamespace().equals("minecraft"),
                rl -> rl.getNamespace().equals("minecraft"));
        List<String> warnings = new ArrayList<>();
        QuestLine pruned = QuestPrune.prune(line, checks, warnings);
        assertEquals(List.of("b", "c"), pruned.quests().stream().map(Quest::id).toList());
        assertEquals(1, pruned.quests().get(0).objectives().size());
        assertEquals(1, pruned.quests().get(0).rewards().items().size());
        assertEquals(5, pruned.quests().get(0).rewards().gold());
        assertTrue(warnings.size() >= 2, warnings.toString());
        assertEquals(3, line.quests().size());
    }
}
