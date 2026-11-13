package org.gettserver.gettcore.editor;

import static org.gettserver.gettcore.editor.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.gettserver.gettcore.data.*;
import net.minecraft.core.BlockPos;
import org.gettserver.gettcore.pet.PetConfig;
import org.gettserver.gettcore.protect.ProtectConfig;
import org.gettserver.gettcore.quest.*;
import org.gettserver.gettcore.reinforce.ReinforceConfig;
import org.gettserver.gettcore.skill.BossConfig;
import org.junit.jupiter.api.Test;

class EditorJsonTest {
    private static final String FULL =
            "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{},\"boss_points\":[],\"pets\":{},\"protect\":{},\"reinforce\":{},\"hunting\":{},\"mobs\":{},\"equipment\":{},\"upgrade\":{}}";

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
                        new BlockPos(30, 120, 40), new ProtectConfig.Flags(true, false, true)))),
                reinforce());
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
        // The web page reads/writes reinforce.json's field names directly.
        var reinforce = root.getAsJsonObject("reinforce");
        assertEquals(3, reinforce.get("maxLevel").getAsInt(), json);
        assertEquals("[95.0,62.5,30.0]", reinforce.get("successPercent").toString(), json);
        assertEquals("[1,2,4]", reinforce.get("stoneCost").toString(), json);
        assertEquals(12.5, reinforce.get("gunDamagePercentPerLevel").getAsDouble(), json);
        assertEquals(8, reinforce.get("meleeDamagePercentPerLevel").getAsDouble(), json);
        assertEquals(3, reinforce.get("shieldSpeedPercentPerLevel").getAsDouble(), json);
        assertEquals(2.5, reinforce.get("armorHealthPerLevel").getAsDouble(), json);
        assertEquals(40, reinforce.get("armorHealthMax").getAsDouble(), json);
        assertEquals("[{\"target\":\"shield\",\"level\":2,\"effect\":\"minecraft:speed\",\"amplifier\":1}]",
                reinforce.get("milestones").toString(), json);
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
        String json = "{\"shops\":{},\"dialogs\":{},\"npcs\":{},\"quests\":{},\"boss_points\":[{\"id\":\"x\"}],\"pets\":{},\"protect\":{},\"reinforce\":{},\"hunting\":{},\"mobs\":{},\"equipment\":{},\"upgrade\":{}}";
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
        for (String section : List.of("shops", "dialogs", "npcs", "quests", "boss_points", "pets", "protect", "reinforce", "hunting", "mobs", "equipment", "upgrade")) {
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
        // A bundle without the key at all (e.g. a page from before the reinforce tab) is rejected too.
        IllegalArgumentException rf = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace(",\"reinforce\":{}", "")));
        IllegalArgumentException hu = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace(",\"hunting\":{}", "")));
        assertTrue(hu.getMessage().startsWith("hunting: missing section"), hu.getMessage());
        assertTrue(rf.getMessage().startsWith("reinforce: missing section"), rf.getMessage());
        assertEquals(new EditorBundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of(), List.of(), PetConfig.DEFAULT,
                ProtectConfig.DEFAULT, ReinforceConfig.DEFAULT), EditorJson.decode(FULL));
    }

    @Test
    void huntingAndMobsShape() {
        var zone = new org.gettserver.gettcore.hunting.HuntingConfig.Zone("숲", net.minecraft.world.level.Level.OVERWORLD,
                new BlockPos(-10, 60, 5), new BlockPos(30, 90, 40), 6, 15, List.of(
                new org.gettserver.gettcore.hunting.HuntingConfig.Entry("큰 좀비", rl("minecraft:zombie"), 80, 6, 0.3,
                        2, 1.4, 3, 25, 1.5)));
        var hunting = new org.gettserver.gettcore.hunting.HuntingConfig(List.of(zone));
        var mobs = new org.gettserver.gettcore.mobs.MobStatsConfig(Map.of("minecraft:zombie",
                new org.gettserver.gettcore.mobs.MobStatsConfig.Stats(java.util.Optional.of(40.0),
                        java.util.Optional.empty(), java.util.Optional.of(0.3), java.util.Optional.empty())));
        EditorBundle base = bundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of());
        EditorBundle b = new EditorBundle(base.shops(), base.dialogs(), base.npcs(), base.quests(), base.bossPoints(),
                base.pets(), base.protect(), base.reinforce(), hunting, mobs);
        String json = EditorJson.encode(b, null);
        assertEquals(b, EditorJson.decode(json));
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        // The web page reads/writes these field names directly.
        var z = root.getAsJsonObject("hunting").getAsJsonArray("zones").get(0).getAsJsonObject();
        assertEquals("숲", z.get("id").getAsString());
        assertEquals("minecraft:overworld", z.get("dimension").getAsString());
        assertEquals("[-10,60,5]", z.get("min").toString());
        assertEquals(6, z.get("maxAlive").getAsInt());
        assertEquals(15, z.get("refillSeconds").getAsInt());
        var e = z.getAsJsonArray("entries").get(0).getAsJsonObject();
        for (String f : List.of("name", "entity", "health", "attack", "speed", "armor", "scale", "weight", "gold",
                "xpMultiplier")) {
            assertTrue(e.has(f), f + " in " + e);
        }
        assertEquals("{\"minecraft:zombie\":{\"health\":40.0,\"speed\":0.3}}",
                root.getAsJsonObject("mobs").getAsJsonObject("overrides").toString());
    }

    @Test
    void equipmentShape() {
        var eq = new org.gettserver.gettcore.equipment.EquipmentConfig(Map.of("minecraft:diamond_sword",
                new org.gettserver.gettcore.equipment.EquipmentConfig.Values(java.util.Optional.of(9.0),
                        java.util.Optional.of(1.2), java.util.Optional.empty(), java.util.Optional.empty(),
                        java.util.Optional.of(0.1))));
        EditorBundle base = bundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of());
        EditorBundle b = new EditorBundle(base.shops(), base.dialogs(), base.npcs(), base.quests(), base.bossPoints(),
                base.pets(), base.protect(), base.reinforce(), base.hunting(), base.mobs(), eq);
        String json = EditorJson.encode(b, null);
        assertEquals(b, EditorJson.decode(json));
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        // The web page reads/writes these field names directly.
        assertEquals(com.google.gson.JsonParser.parseString(
                "{\"minecraft:diamond_sword\":{\"attackDamage\":9.0,\"attackSpeed\":1.2,\"knockbackResistance\":0.1}}"),
                root.getAsJsonObject("equipment").getAsJsonObject("items"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace(",\"equipment\":{}", "")));
        assertTrue(e.getMessage().startsWith("equipment: missing section"), e.getMessage());
    }

    @Test
    void upgradeShape() {
        var up = new org.gettserver.gettcore.upgrade.UpgradeConfig(List.of(
                new org.gettserver.gettcore.upgrade.UpgradeConfig.Step("magistuarmory:iron_pike",
                        "magistuarmory:diamond_pike", "minecraft:diamond", 3, 0)));
        EditorBundle base = bundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of());
        EditorBundle b = new EditorBundle(base.shops(), base.dialogs(), base.npcs(), base.quests(), base.bossPoints(),
                base.pets(), base.protect(), base.reinforce(), base.hunting(), base.mobs(), base.equipment(), up);
        String json = EditorJson.encode(b, null);
        assertEquals(b, EditorJson.decode(json));
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        // The web page reads/writes these field names directly.
        assertEquals(com.google.gson.JsonParser.parseString("[{\"from\":\"magistuarmory:iron_pike\","
                        + "\"to\":\"magistuarmory:diamond_pike\",\"material\":\"minecraft:diamond\",\"count\":3,\"gold\":0}]"),
                root.getAsJsonObject("upgrade").getAsJsonArray("steps"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> EditorJson.decode(FULL.replace(",\"upgrade\":{}", "")));
        assertTrue(e.getMessage().startsWith("upgrade: missing section"), e.getMessage());
    }

    @Test
    void bossesSectionRoundTrips() {
        EditorBundle base = bundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of());
        EditorBundle b = new EditorBundle(base.shops(), base.dialogs(), base.npcs(), base.quests(), base.bossPoints(),
                base.pets(), base.protect(), base.reinforce(), base.hunting(), base.mobs(), base.equipment(),
                base.upgrade(), BossConfig.DEFAULT);
        String json = EditorJson.encode(b, null);
        assertEquals(b, EditorJson.decode(json));
        var root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        // The web page reads/writes these field names directly (every field written, as the codec does).
        var giraffe = root.getAsJsonObject("bosses").getAsJsonObject("bosses").getAsJsonObject("gettcore:boss_giraffe");
        assertEquals("after", giraffe.get("aggro").getAsString());
        var ramgak = giraffe.getAsJsonArray("skills").get(0).getAsJsonObject();
        assertEquals("ramgak", ramgak.get("id").getAsString());
        assertEquals("wind_line", ramgak.get("type").getAsString());
        assertEquals(30.0, ramgak.getAsJsonObject("shape").get("length").getAsDouble());
        assertTrue(ramgak.getAsJsonObject("terrain").get("enabled").getAsBoolean());
    }

    @Test
    void bundleWithoutBossesSectionKeepsTheCurrentConfig() {
        // An editor page from before bosses.json existed (a stale tab) must not reset bosses.json to defaults.
        BossConfig current = withGiraffe(new BossConfig.Boss(BossConfig.Aggro.FIRST, 30, 50, 20, 60,
                Optional.empty(), Optional.empty(), 0, 32, 0, 1, List.of()));
        assertEquals(current, EditorJson.decode(FULL, current).bosses());
        assertEquals(current, EditorJson.decode(FULL.substring(0, FULL.length() - 1) + ",\"bosses\":null}", current).bosses());
    }

    @Test
    void bundleWithBossesSectionIgnoresTheCurrentConfig() {
        BossConfig current = withGiraffe(new BossConfig.Boss(BossConfig.Aggro.FIRST, 30, 50, 20, 60,
                Optional.empty(), Optional.empty(), 0, 32, 0, 1, List.of()));
        String json = EditorJson.encode(EditorJson.decode(FULL, BossConfig.DEFAULT), null);
        assertEquals(BossConfig.DEFAULT, EditorJson.decode(json, current).bosses());
    }

    @Test
    void detectorSectionRoundTripsAndIsOptional() {
        var custom = new org.gettserver.gettcore.detector.DetectorConfig(10, 30, 0, 100, List.of(),
                List.of(new org.gettserver.gettcore.detector.DetectorConfig.Target("a", List.of("a:x"), List.of("a:y"),
                        true)));
        EditorBundle base = bundle(Map.of(), Map.of(), NpcMapping.EMPTY, Map.of());
        EditorBundle b = new EditorBundle(base.shops(), base.dialogs(), base.npcs(), base.quests(), base.bossPoints(),
                base.pets(), base.protect(), base.reinforce(), base.hunting(), base.mobs(), base.equipment(),
                base.upgrade(), BossConfig.DEFAULT, custom);
        String json = EditorJson.encode(b, null);
        assertEquals(b, EditorJson.decode(json));
        assertEquals(10, com.google.gson.JsonParser.parseString(json).getAsJsonObject().getAsJsonObject("detector")
                .get("range").getAsInt());

        // A page from before detector.json existed: default, or the config currently loaded.
        assertEquals(org.gettserver.gettcore.detector.DetectorConfig.DEFAULT, EditorJson.decode(FULL).detector());
        assertEquals(custom, EditorJson.decode(FULL, BossConfig.DEFAULT, custom).detector());
        assertEquals(custom, EditorJson.decode(FULL.substring(0, FULL.length() - 1) + ",\"detector\":null}",
                BossConfig.DEFAULT, custom).detector());
    }
}
