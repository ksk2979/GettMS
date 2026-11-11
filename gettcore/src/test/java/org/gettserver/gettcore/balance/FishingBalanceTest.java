package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 낚시(Tide) 가중치: 카탈로그, 사본 = 원본 + 바꾼 값만, 0 = 안 나옴, 내용물에 아이템 추가. */
class FishingBalanceTest {
    @TempDir Path pack;

    // Trimmed from Tide 2.1.1 data files (unknown fields kept on purpose).
    static final String ARAPAIMA = """
            {"bucket":"tide:arapaima_bucket","conditions":[{"type":"tide:found_in","biomes":["#tide:has_jungle_fish","#tide:has_swamp_fish"]},
              {"type":"tide:freshwater"},{"type":"tide:dimension","dimensions":["minecraft:overworld"]},
              {"type":"tide:fluid","fluid":"water"},{"type":"tide:above","y":40}],
             "fish":"tide:arapaima","journal_profile":{"group":"freshwater","rarity":"rare"},
             "selection_weight":9.0,"selection_quality":0.5,"speed":1.7}""";
    static final String COD = """
            {"conditions":[{"type":"tide:saltwater"}],"fish":"minecraft:cod","journal_profile":{"group":"saltwater"},
             "selection_weight":30.0}""";
    static final String OTHER_MOD_FISH = """
            {"conditions":[],"fish":"hybrid_aquatic:tuna","journal_profile":{},"selection_weight":5.0}""";
    static final String VARIANT = "{\"parent\":\"tide:carp\",\"fish\":\"minecraft:cod\",\"journal_profile\":{}}";
    static final String JUNK_GROUP = """
            {"conditions":[{"type":"tide:fluid","fluid":"water"},{"type":"tide:above","y":40}],
             "loot_table":"minecraft:gameplay/fishing/junk","quality":-2.0,"weight":10.0}""";
    static final String TREASURE_GROUP = """
            {"conditions":[{"type":"tide:open_water","in_open_water":true}],
             "loot_table":"minecraft:gameplay/fishing/treasure","quality":2.0,"weight":5.0}""";
    static final String CRATE = """
            {"block":{"type":"minecraft:simple_state_provider"},"conditions":[{"type":"tide:fluid","fluid":"lava"}],
             "loot_table":"tide:chests/crates/surface_lava","weight":1.0}""";
    static final String JUNK_TABLE = """
            {"type":"minecraft:fishing","pools":[{"bonus_rolls":0.0,"rolls":1.0,"entries":[
              {"type":"minecraft:item","name":"minecraft:lily_pad","weight":17},
              {"type":"minecraft:item","name":"minecraft:leather_boots","weight":10,
               "functions":[{"function":"minecraft:set_damage","damage":{"min":0.0,"max":0.9}}]},
              {"type":"minecraft:item","name":"minecraft:bowl","weight":10},
              {"type":"minecraft:item","name":"minecraft:stick"}]}]}""";
    static final String TWO_POOLS = "{\"pools\":[{\"entries\":[{\"type\":\"minecraft:item\",\"name\":\"a:b\"}]},{\"entries\":[]}]}";

    static final ResourceLocation ARA = ResourceLocation.parse("tide:fishing/fish/freshwater/arapaima.json");
    static final ResourceLocation COD_F = ResourceLocation.parse("minecraft:fishing/fish/saltwater/cod.json");
    static final ResourceLocation JUNK_G = ResourceLocation.parse("tide:fishing/loot/surface_junk.json");
    static final ResourceLocation TREASURE_G = ResourceLocation.parse("tide:fishing/loot/surface_treasure.json");
    static final ResourceLocation CRATE_F = ResourceLocation.parse("tide:fishing/crates/surface_lava.json");
    static final String JUNK = "minecraft:gameplay/fishing/junk";
    static final ResourceLocation JUNK_FILE = ResourceLocation.parse("minecraft:loot_table/gameplay/fishing/junk.json");

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static FishingBalance.Originals originals() {
        Map<ResourceLocation, JsonObject> fish = new LinkedHashMap<>();
        fish.put(ARA, json(ARAPAIMA));
        fish.put(COD_F, json(COD));
        fish.put(ResourceLocation.parse("hybrid_aquatic:fishing/fish/tuna.json"), json(OTHER_MOD_FISH));
        fish.put(ResourceLocation.parse("hybrid_aquatic:fishing/fish/misc/carp.json"), json(VARIANT));
        Map<ResourceLocation, JsonObject> loot = new LinkedHashMap<>();
        loot.put(JUNK_G, json(JUNK_GROUP));
        loot.put(TREASURE_G, json(TREASURE_GROUP));
        Map<ResourceLocation, JsonObject> crates = new LinkedHashMap<>();
        crates.put(CRATE_F, json(CRATE));
        Map<String, JsonObject> tables = new LinkedHashMap<>();
        tables.put(JUNK, json(JUNK_TABLE));
        tables.put("minecraft:gameplay/fishing/treasure", json(TWO_POOLS));
        return new FishingBalance.Originals(fish, loot, crates, tables);
    }

    static boolean installed(ResourceLocation item) {
        return !item.getNamespace().equals("hybrid_aquatic") && !item.getNamespace().equals("gone");
    }

    static JsonObject row(JsonArray rows, String id) {
        for (var e : rows) if (e.getAsJsonObject().get("id").getAsString().equals(id)) return e.getAsJsonObject();
        return null;
    }

    @Test
    void tableIdsAndFiles() {
        assertEquals(JUNK_FILE, FishingBalance.tableFile(JUNK));
        assertEquals(List.of(JUNK, "minecraft:gameplay/fishing/treasure"), FishingBalance.tableIds(originals().loot()));
    }

    @Test
    void catalogListsFishOfInstalledModsWithWhereNotes() {
        JsonObject cat = FishingBalance.catalog(originals(), FishingBalanceTest::installed);
        JsonArray fish = cat.getAsJsonArray("fish");
        assertEquals(2, fish.size(), "other mod's fish and weightless variants are left out: " + fish);
        JsonObject ara = row(fish, "tide:fishing/fish/freshwater/arapaima");
        assertEquals("tide:arapaima", ara.get("item").getAsString());
        assertEquals(9.0, ara.get("weight").getAsDouble());
        assertEquals("rare", ara.get("rarity").getAsString());
        assertEquals("freshwater", ara.get("group").getAsString());
        assertEquals("[\"정글·늪\",\"민물\",\"지상\"]", ara.get("where").toString());
        assertEquals("common", row(fish, "minecraft:fishing/fish/saltwater/cod").get("rarity").getAsString());

        JsonObject junk = row(cat.getAsJsonArray("loot"), "tide:fishing/loot/surface_junk");
        assertEquals(JUNK, junk.get("table").getAsString());
        assertEquals(10.0, junk.get("weight").getAsDouble());
        assertEquals(-2.0, junk.get("quality").getAsDouble());
        assertEquals(1, cat.getAsJsonArray("crates").size());
        assertEquals("[\"용암\"]", cat.getAsJsonArray("crates").get(0).getAsJsonObject().get("where").toString());

        JsonObject table = row(cat.getAsJsonArray("tables"), JUNK);
        assertTrue(table.get("editable").getAsBoolean());
        JsonArray entries = table.getAsJsonArray("entries");
        assertEquals(4, entries.size());
        assertEquals("1", entries.get(1).getAsJsonObject().get("key").getAsString());
        assertEquals("minecraft:leather_boots", entries.get(1).getAsJsonObject().get("item").getAsString());
        assertEquals("[\"닳은 상태\"]", entries.get(1).getAsJsonObject().get("notes").toString());
        assertEquals(1, entries.get(3).getAsJsonObject().get("weight").getAsInt(), "no weight = 1");
        assertFalse(row(cat.getAsJsonArray("tables"), "minecraft:gameplay/fishing/treasure").get("editable").getAsBoolean());
    }

    @Test
    void weightCopiesKeepEverythingElseAndZeroDropsQuality() throws Exception {
        JsonObject desired = json("""
                {"fish":{"tide:fishing/fish/freshwater/arapaima":0,"minecraft:fishing/fish/saltwater/cod":12.5},
                 "loot":{"tide:fishing/loot/surface_junk":2},"crates":{"tide:fishing/crates/surface_lava":0}}""");
        assertEquals(List.of(), FishingBalance.validate(desired));
        FishingBalance.Outcome o = FishingBalance.reconcile(originals(), pack, desired, FishingBalanceTest::installed);
        assertEquals(4, o.written());
        assertEquals(List.of(), o.warnings());

        JsonObject ara = PackFiles.read(pack, ARA).orElseThrow();
        assertEquals(0.0, ara.get("selection_weight").getAsDouble());
        assertFalse(ara.has("selection_quality"), "luck must not bring a 0 fish back");
        assertEquals("tide:arapaima_bucket", ara.get("bucket").getAsString());
        assertEquals(json(ARAPAIMA).get("conditions"), ara.get("conditions"));
        JsonObject junk = PackFiles.read(pack, JUNK_G).orElseThrow();
        assertEquals(2.0, junk.get("weight").getAsDouble());
        assertEquals(-2.0, junk.get("quality").getAsDouble());

        JsonObject state = FishingBalance.state(originals(), pack);
        assertEquals(desired.get("fish"), state.get("fish"));
        assertEquals(2.0, state.getAsJsonObject("loot").get("tide:fishing/loot/surface_junk").getAsDouble());
        assertEquals(0.0, state.getAsJsonObject("crates").get("tide:fishing/crates/surface_lava").getAsDouble());

        // Back to the original value = copy removed; saving the same thing again changes nothing.
        desired.getAsJsonObject("fish").addProperty("minecraft:fishing/fish/saltwater/cod", 30);
        FishingBalance.Outcome again = FishingBalance.reconcile(originals(), pack, desired, FishingBalanceTest::installed);
        assertEquals(0, again.written());
        assertEquals(1, again.deleted());
        assertTrue(PackFiles.read(pack, COD_F).isEmpty());
        assertFalse(FishingBalance.reconcile(originals(), pack, desired, FishingBalanceTest::installed).changed());
    }

    @Test
    void tableWeightsRemovalsAndAddedItemsRoundTrip() throws Exception {
        JsonObject desired = json("""
                {"tables":{"minecraft:gameplay/fishing/junk":{"weights":{"0":3,"1":0},
                  "added":[{"item":"gettcore:reinforce_stone","weight":4,"min":1,"max":3},
                           {"item":"minecraft:diamond","weight":1,"min":2,"max":2},
                           {"item":"minecraft:emerald","weight":2,"min":1,"max":1}]}}}""");
        assertEquals(List.of(), FishingBalance.validate(desired));
        FishingBalance.Outcome o = FishingBalance.reconcile(originals(), pack, desired, FishingBalanceTest::installed);
        assertEquals(1, o.written());

        JsonObject copy = PackFiles.read(pack, JUNK_FILE).orElseThrow();
        assertEquals("minecraft:fishing", copy.get("type").getAsString());
        JsonArray entries = copy.getAsJsonArray("pools").get(0).getAsJsonObject().getAsJsonArray("entries");
        assertEquals(6, entries.size(), "boots removed, three added");
        assertEquals(3, entries.get(0).getAsJsonObject().get("weight").getAsInt());
        assertEquals("minecraft:bowl", entries.get(1).getAsJsonObject().get("name").getAsString());
        assertFalse(entries.get(2).getAsJsonObject().has("weight"), "untouched entries stay as they were");
        JsonObject stone = entries.get(3).getAsJsonObject();
        assertEquals("gettcore:reinforce_stone", stone.get("name").getAsString());
        assertEquals("minecraft:uniform", stone.getAsJsonArray("functions").get(0).getAsJsonObject()
                .getAsJsonObject("count").get("type").getAsString());
        assertEquals(2, entries.get(4).getAsJsonObject().getAsJsonArray("functions").get(0).getAsJsonObject()
                .get("count").getAsInt());
        assertFalse(entries.get(5).getAsJsonObject().has("functions"), "one each needs no count function");

        JsonObject state = FishingBalance.state(originals(), pack).getAsJsonObject("tables");
        assertEquals(desired.getAsJsonObject("tables"), state);
        assertFalse(FishingBalance.reconcile(originals(), pack, desired, FishingBalanceTest::installed).changed());

        // Empty edits = original = copy removed.
        FishingBalance.Outcome back = FishingBalance.reconcile(originals(), pack, json("{\"tables\":{}}"),
                FishingBalanceTest::installed);
        assertEquals(1, back.deleted());
    }

    @Test
    void unknownItemsEmptyTablesAndOddTablesAreSkippedWithWarnings() throws Exception {
        JsonObject desired = json("""
                {"fish":{"gone:fishing/fish/x":3},
                 "tables":{"minecraft:gameplay/fishing/junk":{"weights":{"0":0,"1":0,"2":0,"3":0},
                    "added":[{"item":"gone:thing","weight":1,"min":1,"max":1}]},
                  "minecraft:gameplay/fishing/treasure":{"weights":{"0":5}},
                  "gone:table":{"weights":{"0":1}}}}""");
        FishingBalance.Outcome o = FishingBalance.reconcile(originals(), pack, desired, FishingBalanceTest::installed);
        assertEquals(0, o.written());
        assertEquals(5, o.warnings().size(), o.warnings().toString());
        assertTrue(o.warnings().stream().anyMatch(w -> w.contains("묶음 가중치를 0")), o.warnings().toString());
    }

    @Test
    void validateRejectsBadNumbers() {
        List<String> errs = new ArrayList<>(FishingBalance.validate(json("""
                {"fish":{"a:b":-1,"a:c":"x"},"loot":[],
                 "tables":{"t:t":{"weights":{"0":1.5,"1":-1},
                   "added":[{"item":"Bad Id","weight":0,"min":3,"max":2},{"item":"a:b","weight":1,"min":1,"max":65}]}}}"""))) ;
        assertEquals(9, errs.size(), errs.toString());
        assertEquals(List.of(), FishingBalance.validate(json("{}")));
    }
}
