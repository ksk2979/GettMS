package org.gettserver.gettcore.editor;

import static org.gettserver.gettcore.editor.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.data.*;
import org.gettserver.gettcore.quest.*;
import org.junit.jupiter.api.Test;

class EditorJsonTest {
    @Test
    void roundTrip() {
        EditorBundle b = bundle(
                Map.of("farmer", shop("farmer", entry("minecraft:wheat", 10, 5))),
                Map.of("chief", dialog("chief", "hello", Map.of("hello",
                        new DialogNode("안녕", List.of(choice(ChoiceAction.Type.QUEST, null)))))),
                new NpcMapping(Map.of(), Map.of("이장", "chief")),
                Map.of("main", new QuestLine("main", "메인", "끝", List.of(
                        quest("q1", npc("이장"), List.of(), Rewards.EMPTY)))));
        EditorRefs refs = new EditorRefs(List.of(new EditorRefs.AdvRef("minecraft:story/mine_diamond", "다이아몬드!")),
                List.of("gone:item"), List.of("gone:mob"));
        String json = EditorJson.encode(b, refs);
        assertTrue(json.contains("\"refs\""));
        assertTrue(json.contains("mine_diamond"));
        assertTrue(json.contains("gone:item"));
        assertEquals(b, EditorJson.decode(json));
        assertEquals(b, EditorJson.decode(EditorJson.encode(b, null)));
        assertFalse(EditorJson.encode(b, null).contains("\"refs\""));
    }

    @Test
    void decodeErrorNamesPath() {
        String json = "{\"shops\":{\"farmer\":{\"id\":\"farmer\"}},\"dialogs\":{},\"npcs\":{},\"quests\":{}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorJson.decode(json));
        assertTrue(e.getMessage().startsWith("shops.farmer"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EditorJson.decode("not json"));
    }

    @Test
    void missingSectionIsAnError() {
        // Full-replace save: a missing section must not be read as "delete everything in it".
        for (String section : List.of("shops", "dialogs", "npcs", "quests")) {
            String json = "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{}}"
                    .replace("\"" + section + "\":{}", "\"x\":{}");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorJson.decode(json),
                    section);
            assertTrue(e.getMessage().startsWith(section), e.getMessage());
        }
        assertEquals(new EditorBundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of()),
                EditorJson.decode("{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{}}"));
    }
}
