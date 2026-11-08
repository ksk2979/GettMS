package org.gettserver.gettcore.editor;

import static org.gettserver.gettcore.editor.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.data.*;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.quest.*;
import org.junit.jupiter.api.Test;

class EditorJsonTest {
    private static final String FULL =
            "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{},\"boss_points\":[],\"pets\":{}}";

    @Test
    void roundTrip() {
        EditorBundle b = bundle(
                Map.of("farmer", shop("farmer", entry("minecraft:wheat", 10, 5))),
                Map.of("chief", dialog("chief", "hello", Map.of("hello",
                        new DialogNode("안녕", List.of(choice(ChoiceAction.Type.QUEST, null)))))),
                new NpcMapping(Map.of(), Map.of("이장", "chief")),
                Map.of("main", new QuestLine("main", "메인", "끝", List.of(
                        quest("q1", npc("이장"), List.of(), Rewards.EMPTY)))),
                List.of(bossPoint("den", "gettcore:boss_lion", 20, 2, 45)),
                pets(species("gettcore:boss_lion", true)));
        EditorRefs refs = new EditorRefs(List.of(new EditorRefs.AdvRef("minecraft:story/mine_diamond", "다이아몬드!")),
                List.of("gone:item"), List.of("gone:mob"));
        String json = EditorJson.encode(b, refs);
        assertTrue(json.contains("\"refs\""));
        assertTrue(json.contains("mine_diamond"));
        assertTrue(json.contains("gone:item"));
        assertEquals(b, EditorJson.decode(json));
        assertEquals(b, EditorJson.decode(EditorJson.encode(b, null)));
        assertFalse(EditorJson.encode(b, null).contains("\"refs\""));
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertEquals("den", root.getAsJsonArray("boss_points").get(0).getAsJsonObject().get("id").getAsString());
        var pets = root.getAsJsonObject("pets");
        assertTrue(pets.getAsJsonObject("species").has("gettcore:boss_lion"), json);
    }

    @Test
    void petChanceRangesRoundTrip() {
        var d = PetConfig.DEFAULT.capture();
        var ball = new java.util.EnumMap<>(d.ballChance());
        ball.put(org.gettserver.gettcore.pet.CaptureTier.IRON, new PetConfig.ChanceRange(4, 30));
        PetConfig pets = new PetConfig(PetConfig.DEFAULT.healthbar(),
                new PetConfig.Capture(d.minPercent(), d.maxPercent(), ball, d.bossBallChance(), d.obtainPercent()),
                Map.of());
        EditorBundle b = bundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of(), List.of(), pets);
        String json = EditorJson.encode(b, null);
        var iron = com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("pets")
                .getAsJsonObject("capture").getAsJsonObject("ballChance").getAsJsonObject("iron");
        assertEquals(4, iron.get("base").getAsDouble(), json);
        assertEquals(30, iron.get("max").getAsDouble(), json);
        assertEquals(b, EditorJson.decode(json));

        // What the editor sends: every tier in object form.
        String editor = FULL.replace("\"pets\":{}", "\"pets\":{\"capture\":{\"ballChance\":{"
                + "\"iron\":{\"base\":4,\"max\":30},\"gold\":{\"base\":8,\"max\":35},"
                + "\"diamond\":{\"base\":12,\"max\":50},\"netherite\":{\"base\":20,\"max\":70}}}}");
        assertEquals(b, EditorJson.decode(editor));
    }

    @Test
    void badBossPointNamesPath() {
        String json = "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{},\"boss_points\":[{\"id\":\"x\"}],\"pets\":{}}";
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorJson.decode(json));
        assertTrue(e.getMessage().startsWith("boss_points"), e.getMessage());
    }

    @Test
    void decodeErrorNamesPath() {
        String json = FULL.replace("\"shops\":{}", "\"shops\":{\"farmer\":{\"id\":\"farmer\"}}");
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorJson.decode(json));
        assertTrue(e.getMessage().startsWith("shops.farmer"), e.getMessage());
        assertThrows(IllegalArgumentException.class, () -> EditorJson.decode("not json"));
    }

    @Test
    void missingSectionIsAnError() {
        // Full-replace save: a missing section must not be read as "delete everything in it".
        for (String section : List.of("shops", "dialogs", "npcs", "quests", "boss_points", "pets")) {
            String json = FULL.replace("\"" + section + "\":" + (section.equals("boss_points") ? "[]" : "{}"), "\"x\":{}");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorJson.decode(json),
                    section);
            assertTrue(e.getMessage().startsWith(section), e.getMessage());
        }
        // null counts as missing too.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace("\"pets\":{}", "\"pets\":null")));
        assertTrue(e.getMessage().startsWith("pets"), e.getMessage());
        assertEquals(new EditorBundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of(), List.of(), PetConfig.DEFAULT),
                EditorJson.decode(FULL));
    }
}
