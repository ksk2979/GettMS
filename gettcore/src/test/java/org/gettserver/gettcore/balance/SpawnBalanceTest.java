package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 등장: 모드 등장 규칙 사본(가중치·무리 수·끄기)과 몹 단위 가중치 배율 파일. */
class SpawnBalanceTest {
    @TempDir Path pack;

    // The real data/wom/neoforge/biome_modifier/spawn_hollow.json (single spawner object).
    static final String HOLLOW = """
            {"type":"neoforge:add_spawns","biomes":"#minecraft:is_overworld",
             "spawners":{"type":"wom:hollow","weight":60,"minCount":1,"maxCount":1}}""";
    static final String PAIR = """
            {"type":"neoforge:add_spawns","biomes":["minecraft:plains","minecraft:forest"],"extra":{"keep":true},
             "spawners":[{"type":"x:a","weight":10,"minCount":1,"maxCount":3},{"type":"x:b","weight":5,"minCount":2,"maxCount":4}]}""";
    static final String FEATURES = "{\"type\":\"neoforge:add_features\",\"biomes\":\"#minecraft:is_overworld\"}";

    static final ResourceLocation HOLLOW_LOC = ResourceLocation.parse("wom:neoforge/biome_modifier/spawn_hollow.json");
    static final ResourceLocation PAIR_LOC = ResourceLocation.parse("x:neoforge/biome_modifier/pair.json");

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static Map<ResourceLocation, JsonObject> originals() {
        Map<ResourceLocation, JsonObject> m = new LinkedHashMap<>();
        m.put(HOLLOW_LOC, json(HOLLOW));
        m.put(PAIR_LOC, json(PAIR));
        m.put(ResourceLocation.parse("create:neoforge/biome_modifier/zinc_ore.json"), json(FEATURES));
        return m;
    }

    @Test
    void ruleIdsAreFileNamesUnderBiomeModifier() {
        assertEquals("wom:spawn_hollow", SpawnBalance.ruleIdOf(HOLLOW_LOC));
        assertEquals(HOLLOW_LOC, SpawnBalance.ruleFileOf("wom:spawn_hollow"));
        assertNull(SpawnBalance.ruleIdOf(ResourceLocation.parse("wom:weapons/x.json")));
    }

    @Test
    void catalogListsOnlyAddSpawnsRules() {
        JsonArray cat = SpawnBalance.ruleCatalog(originals());
        assertEquals(2, cat.size());
        JsonObject h = cat.get(0).getAsJsonObject();
        assertEquals("wom:spawn_hollow", h.get("id").getAsString());
        assertEquals("wom", h.get("mod").getAsString());
        assertEquals("#minecraft:is_overworld", h.get("biomes").getAsString());
        JsonObject s = h.getAsJsonArray("spawners").get(0).getAsJsonObject();
        assertEquals("wom:hollow", s.get("type").getAsString());
        assertEquals(60, s.get("weight").getAsInt());
        assertEquals("minecraft:plains, minecraft:forest",
                cat.get(1).getAsJsonObject().get("biomes").getAsString());
        assertEquals(2, cat.get(1).getAsJsonObject().getAsJsonArray("spawners").size());
    }

    @Test
    void disableTurnsTheCopyIntoNoneAndKeepsEverythingElse() throws Exception {
        SpawnBalance.Outcome o = SpawnBalance.reconcileRules(originals(), pack, json("{\"wom:spawn_hollow\":{\"disabled\":true}}"));
        assertEquals(1, o.written());
        JsonObject copy = PackFiles.read(pack, HOLLOW_LOC).orElseThrow();
        JsonObject expected = json(HOLLOW);
        expected.addProperty("type", "neoforge:none");
        assertEquals(expected, copy);
        assertEquals(json("{\"wom:spawn_hollow\":{\"disabled\":true}}"), SpawnBalance.ruleState(originals(), pack));
    }

    @Test
    void weightAndCountsChangeInTheSameShape() throws Exception {
        SpawnBalance.reconcileRules(originals(), pack, json(
                "{\"wom:spawn_hollow\":{\"spawners\":[{\"weight\":20,\"minCount\":1,\"maxCount\":2}]},"
                        + "\"x:pair\":{\"spawners\":[{\"weight\":10,\"minCount\":1,\"maxCount\":3},{\"weight\":1}]}}"));
        JsonObject h = PackFiles.read(pack, HOLLOW_LOC).orElseThrow();
        assertTrue(h.get("spawners").isJsonObject(), "single object stays a single object");
        assertEquals(20, h.getAsJsonObject("spawners").get("weight").getAsInt());
        assertEquals(2, h.getAsJsonObject("spawners").get("maxCount").getAsInt());
        assertEquals("wom:hollow", h.getAsJsonObject("spawners").get("type").getAsString());
        JsonObject p = PackFiles.read(pack, PAIR_LOC).orElseThrow();
        assertTrue(p.getAsJsonObject("extra").get("keep").getAsBoolean(), "unknown fields kept");
        assertEquals(1, p.getAsJsonArray("spawners").get(1).getAsJsonObject().get("weight").getAsInt());
        assertEquals(2, p.getAsJsonArray("spawners").get(1).getAsJsonObject().get("minCount").getAsInt());
        JsonObject state = SpawnBalance.ruleState(originals(), pack);
        assertEquals(json("{\"weight\":1,\"minCount\":2,\"maxCount\":4}"),
                state.getAsJsonObject("x:pair").getAsJsonArray("spawners").get(1));
    }

    @Test
    void resetDeletesTheCopy() throws Exception {
        SpawnBalance.reconcileRules(originals(), pack, json("{\"wom:spawn_hollow\":{\"disabled\":true}}"));
        SpawnBalance.Outcome o = SpawnBalance.reconcileRules(originals(), pack, json("{}"));
        assertEquals(1, o.deleted());
        assertTrue(PackFiles.read(pack, HOLLOW_LOC).isEmpty());
        SpawnBalance.reconcileRules(originals(), pack, json(
                "{\"wom:spawn_hollow\":{\"disabled\":false,\"spawners\":[{\"weight\":60,\"minCount\":1,\"maxCount\":1}]}}"));
        assertTrue(PackFiles.read(pack, HOLLOW_LOC).isEmpty(), "same as the original: no copy");
    }

    @Test
    void validateRules() {
        assertEquals(List.of(), SpawnBalance.validateRules(json(
                "{\"a:b\":{\"disabled\":true},\"a:c\":{\"spawners\":[{\"weight\":1,\"minCount\":1,\"maxCount\":1}]}}")));
        List<String> errs = SpawnBalance.validateRules(json(
                "{\"a:b\":{\"spawners\":[{\"weight\":0,\"minCount\":3,\"maxCount\":2}]},\"a:c\":{\"disabled\":\"yes\"}}"));
        assertEquals(3, errs.size(), errs.toString());
    }

    @Test
    void validateRulesChecksTheMergedResultAgainstTheOriginal() {
        // x:pair spawner 2 is min 2 / max 4 in the original: only maxCount=1 sent -> merged min 2 > max 1.
        List<String> errs = SpawnBalance.validateRules(originals(), json(
                "{\"x:pair\":{\"spawners\":[null,{\"maxCount\":1}]}}"));
        assertEquals(1, errs.size(), errs.toString());
        assertTrue(errs.get(0).contains("최소 수가 최대 수보다"), errs.toString());
        errs = SpawnBalance.validateRules(originals(), json("{\"wom:spawn_hollow\":{\"spawners\":[{\"minCount\":5}]}}"));
        assertEquals(1, errs.size(), "original max 1 < new min 5: " + errs);
        assertEquals(List.of(), SpawnBalance.validateRules(originals(), json(
                "{\"x:pair\":{\"spawners\":[null,{\"maxCount\":2}]},\"gone:rule\":{\"disabled\":true}}")));
        assertEquals(List.of(), SpawnBalance.validateRules(originals(), json(
                "{\"wom:spawn_hollow\":{\"disabled\":true,\"spawners\":[{\"minCount\":5}]}}")), "off: counts unused");
    }

    @Test
    void mobMultiplierFilesAreOursAndOneMeansNoFile() throws Exception {
        SpawnBalance.Outcome o = SpawnBalance.reconcileMobs(pack, json("{\"minecraft:zombie\":0,\"wom:hollow\":0.5,\"minecraft:cow\":1}"));
        assertEquals(2, o.written());
        JsonObject z = PackFiles.read(pack, SpawnBalance.tweakFileOf(ResourceLocation.parse("minecraft:zombie"))).orElseThrow();
        assertEquals("gettcore:scale_spawns", z.get("type").getAsString());
        assertEquals("minecraft:zombie", z.get("entity").getAsString());
        assertEquals(0.0, z.get("multiplier").getAsDouble());
        assertEquals(json("{\"minecraft:zombie\":0.0,\"wom:hollow\":0.5}"), SpawnBalance.mobState(pack));
        o = SpawnBalance.reconcileMobs(pack, json("{\"wom:hollow\":0.5}"));
        assertEquals(1, o.deleted());
        assertEquals(0, o.written());
        assertEquals(json("{\"wom:hollow\":0.5}"), SpawnBalance.mobState(pack));
    }

    @Test
    void validateMobs() {
        assertEquals(List.of(), SpawnBalance.validateMobs(json("{\"minecraft:zombie\":0,\"a:b\":100}")));
        assertEquals(3, SpawnBalance.validateMobs(json("{\"minecraft:zombie\":-1,\"a:b\":101,\"Bad Id\":1}")).size());
    }

    @Test
    void scaledWeightRoundsAndNeverDropsBelowOneUnlessOff() {
        assertEquals(50, SpawnBalance.scaledWeight(100, 0.5));
        assertEquals(1, SpawnBalance.scaledWeight(1, 0.1));
        assertEquals(0, SpawnBalance.scaledWeight(100, 0));
        assertEquals(300, SpawnBalance.scaledWeight(100, 3));
    }

    @Test
    void mobRowsAggregateBiomeSpawns() {
        JsonArray rows = SpawnBalance.mobRows(List.of(
                new SpawnBalance.Spawn("minecraft:zombie", "monster", 95, 4, 4),
                new SpawnBalance.Spawn("minecraft:zombie", "monster", 19, 4, 4),
                new SpawnBalance.Spawn("wom:hollow", "monster", 60, 1, 1),
                new SpawnBalance.Spawn("minecraft:cow", "creature", 8, 4, 4)), List.of("gone:mob"));
        assertEquals(4, rows.size());
        JsonObject zombie = rows.get(1).getAsJsonObject();   // sorted by id: gone, minecraft:cow, minecraft:zombie, wom
        assertEquals("minecraft:cow", zombie.get("entity").getAsString());
        zombie = rows.get(2).getAsJsonObject();
        assertEquals("minecraft:zombie", zombie.get("entity").getAsString());
        assertEquals(2, zombie.get("biomes").getAsInt());
        assertEquals(19, zombie.get("minWeight").getAsInt());
        assertEquals(95, zombie.get("maxWeight").getAsInt());
        JsonObject gone = rows.get(0).getAsJsonObject();
        assertEquals(0, gone.get("biomes").getAsInt(), "only in our files: shown so it can be turned back on");
    }
}
