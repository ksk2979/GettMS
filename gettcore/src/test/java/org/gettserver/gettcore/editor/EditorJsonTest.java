package org.gettserver.gettcore.editor;

import static org.gettserver.gettcore.editor.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.gettserver.gettcore.data.*;
import net.minecraft.core.BlockPos;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.quest.*;
import org.junit.jupiter.api.Test;

class EditorJsonTest {
    private static final String FULL =
            "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{},\"boss_points\":[],\"pets\":{},\"protect\":{}}";

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
                new PetConfig(PetConfig.DEFAULT.healthbar(), captureWithObtainTables(),
                        Map.of("gettcore:boss_lion", species("gettcore:boss_lion", true),
                                "naturalist:lion", speciesWithOverrides("naturalist:lion", false))),
                new ProtectConfig(true, false, List.of(region("town", new BlockPos(-10, 0, -20),
                        new BlockPos(30, 120, 40), new ProtectConfig.Flags(true, false, true)))));
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
        // The web page reads/writes these shapes: per-tier percent tables, partial species overrides, no legacy key.
        var capture = pets.getAsJsonObject("capture");
        assertEquals(22, capture.getAsJsonObject("obtainChance").get("gold").getAsDouble(), json);
        assertEquals(8, capture.getAsJsonObject("bossObtainChance").get("netherite").getAsDouble(), json);
        assertFalse(capture.has("obtainPercent"), json);
        var lion = pets.getAsJsonObject("species").getAsJsonObject("naturalist:lion");
        assertEquals("{\"gold\":{\"base\":10.0,\"max\":40.0}}", lion.getAsJsonObject("ballChance").toString(), json);
        assertEquals("{\"diamond\":55.0}", lion.getAsJsonObject("obtainChance").toString(), json);
        var boss = pets.getAsJsonObject("species").getAsJsonObject("gettcore:boss_lion");
        assertFalse(boss.has("ballChance") || boss.has("obtainChance") || boss.has("obtainPercent"), json);
        // The web page builds this shape by hand: field names, dimension string, [x,y,z] arrays.
        var protect = root.getAsJsonObject("protect");
        assertTrue(protect.get("explosionsBreakBlocks").getAsBoolean(), json);
        var town = protect.getAsJsonArray("regions").get(0).getAsJsonObject();
        assertEquals("town", town.get("id").getAsString());
        assertEquals("minecraft:overworld", town.get("dimension").getAsString());
        assertEquals("[-10,0,-20]", town.get("min").toString());
        assertEquals("[30,120,40]", town.get("max").toString());
        assertTrue(town.getAsJsonObject("flags").get("fireSpread").getAsBoolean(), json);
    }

    @Test
    void petChanceRangesRoundTrip() {
        var d = PetConfig.DEFAULT.capture();
        var ball = new java.util.EnumMap<>(d.ballChance());
        ball.put(org.gettserver.gettcore.pet.CaptureTier.IRON, new PetConfig.ChanceRange(4, 30));
        PetConfig pets = new PetConfig(PetConfig.DEFAULT.healthbar(),
                new PetConfig.Capture(d.minPercent(), d.maxPercent(), ball, d.bossBallChance(), d.obtainChance(),
                        d.bossObtainChance(), d.throwCooldownTicks()),
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
    void legacyObtainPercentFromOldEditorIsAccepted() {
        // A page cached from before the per-tier tables (or a restored draft) still sends obtainPercent.
        String old = FULL.replace("\"pets\":{}", "\"pets\":{\"capture\":{\"obtainPercent\":33},\"species\":{"
                + "\"naturalist:lion\":{\"kind\":\"fighter\",\"petEntity\":\"gettcore:pet_lion\",\"obtainPercent\":45},"
                + "\"naturalist:bear\":{\"kind\":\"fighter\",\"petEntity\":\"gettcore:pet_lion\",\"obtainPercent\":0},"
                + "\"naturalist:tiger\":{\"kind\":\"fighter\",\"petEntity\":\"gettcore:pet_lion\",\"obtainPercent\":20},"
                + "\"gettcore:boss_lion\":{\"kind\":\"fighter\",\"petEntity\":\"gettcore:pet_lion\",\"boss\":true,"
                + "\"obtainPercent\":20}}}");
        EditorBundle b = EditorJson.decode(old);
        var cap = b.pets().capture();
        for (var t : org.gettserver.gettcore.pet.CaptureTier.values()) {
            assertEquals(33.0, cap.obtainChance().get(t));
            assertEquals(33.0, cap.bossObtainChance().get(t));
            assertEquals(45.0, b.pets().species().get("naturalist:lion").obtainChance().get(t));
            assertEquals(20.0, b.pets().species().get("naturalist:tiger").obtainChance().get(t));
        }
        assertTrue(b.pets().species().get("naturalist:bear").obtainChance().isEmpty());
        // The 1.3.0 editor wrote obtainPercent: 20 on every boss: that is no override.
        assertTrue(b.pets().species().get("gettcore:boss_lion").obtainChance().isEmpty());
        // ...and the old global default 20 leaves the new tier tables at their defaults.
        var oldDefault = EditorJson.decode(FULL.replace("\"pets\":{}", "\"pets\":{\"capture\":{\"obtainPercent\":20}}"))
                .pets().capture();
        assertEquals(org.gettserver.gettcore.pet.PetConfig.DEFAULT.capture().obtainChance(), oldDefault.obtainChance());
        assertEquals(org.gettserver.gettcore.pet.PetConfig.DEFAULT.capture().bossObtainChance(),
                oldDefault.bossObtainChance());
        EditorValidator.Result r = EditorValidator.validate(b, RegistryChecks.ALL_EXIST);
        assertTrue(r.errors().isEmpty(), r.errors().toString());
        assertEquals(b, r.cleaned());
        assertFalse(EditorJson.encode(r.cleaned(), null).contains("obtainPercent"));
    }

    @Test
    void badBossPointNamesPath() {
        String json = "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{},\"boss_points\":[{\"id\":\"x\"}],\"pets\":{},\"protect\":{}}";
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
        for (String section : List.of("shops", "dialogs", "npcs", "quests", "boss_points", "pets", "protect")) {
            String json = FULL.replace("\"" + section + "\":" + (section.equals("boss_points") ? "[]" : "{}"), "\"x\":{}");
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> EditorJson.decode(json),
                    section);
            assertTrue(e.getMessage().startsWith(section), e.getMessage());
        }
        // null counts as missing too.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace("\"pets\":{}", "\"pets\":null")));
        assertTrue(e.getMessage().startsWith("pets"), e.getMessage());
        IllegalArgumentException p = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace("\"protect\":{}", "\"protect\":null")));
        assertTrue(p.getMessage().startsWith("protect: missing section"), p.getMessage());
        assertEquals(new EditorBundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of(), List.of(), PetConfig.DEFAULT,
                ProtectConfig.DEFAULT), EditorJson.decode(FULL));
    }
}
