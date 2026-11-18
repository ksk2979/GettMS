package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 모드 지형 생성 막기: 구조물 세트는 structures 를 비운 사본, 지형 요소 추가(광석 등)는 neoforge:none 사본. */
class WorldgenBalanceTest {
    @TempDir Path pack;

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static ResourceLocation loc(String s) {
        return ResourceLocation.parse(s);
    }

    static final ResourceLocation CITADEL = loc("irons_spellbooks:worldgen/structure_set/citadel.json");
    static final ResourceLocation VILLAGES = loc("minecraft:worldgen/structure_set/villages.json");
    static final ResourceLocation ORE = loc("irons_spellbooks:neoforge/biome_modifier/add_mithril_ore.json");
    static final ResourceLocation SPAWNS = loc("irons_spellbooks:neoforge/biome_modifier/necromancer_spawns.json");

    static Map<ResourceLocation, JsonObject> structures() {
        Map<ResourceLocation, JsonObject> m = new HashMap<>();
        m.put(CITADEL, json("{\"structures\":[{\"structure\":\"irons_spellbooks:citadel\",\"weight\":1}],"
                + "\"placement\":{\"type\":\"minecraft:random_spread\",\"salt\":199995600,\"spacing\":100,\"separation\":50}}"));
        m.put(VILLAGES, json("{\"structures\":[{\"structure\":\"minecraft:village_plains\",\"weight\":1}],"
                + "\"placement\":{\"type\":\"minecraft:random_spread\",\"salt\":1,\"spacing\":34,\"separation\":8}}"));
        return m;
    }

    static Map<ResourceLocation, JsonObject> modifiers() {
        Map<ResourceLocation, JsonObject> m = new HashMap<>();
        m.put(ORE, json("{\"type\":\"neoforge:add_features\",\"biomes\":\"#minecraft:is_overworld\","
                + "\"features\":\"irons_spellbooks:ore_mithril_placement\",\"step\":\"underground_ores\"}"));
        m.put(SPAWNS, json("{\"type\":\"neoforge:add_spawns\",\"biomes\":\"#minecraft:is_overworld\","
                + "\"spawners\":[{\"type\":\"irons_spellbooks:necromancer\",\"weight\":18,\"minCount\":1,\"maxCount\":1}]}"));
        return m;
    }

    @Test
    void idsRoundTrip() {
        assertEquals("structure/irons_spellbooks:citadel", WorldgenBalance.idOf(CITADEL));
        assertEquals("feature/irons_spellbooks:add_mithril_ore", WorldgenBalance.idOf(ORE));
        assertEquals(CITADEL, WorldgenBalance.fileOf("structure/irons_spellbooks:citadel"));
        assertEquals(ORE, WorldgenBalance.fileOf("feature/irons_spellbooks:add_mithril_ore"));
        assertNull(WorldgenBalance.idOf(loc("a:recipe/x.json")));
        assertNull(WorldgenBalance.fileOf("citadel"));
        assertNull(WorldgenBalance.fileOf("other/a:b"));
    }

    @Test
    void listsModStructuresAndFeatureModifiersOnly() {
        Map<String, WorldgenBalance.Row> rows = WorldgenBalance.rows(structures(), modifiers());
        // vanilla villages and the spawn rule (the spawn tab's job) are not listed
        assertEquals(Set.of("structure/irons_spellbooks:citadel", "feature/irons_spellbooks:add_mithril_ore"), rows.keySet());
        assertEquals("irons_spellbooks:citadel", rows.get("structure/irons_spellbooks:citadel").detail());
        assertEquals("irons_spellbooks:ore_mithril_placement", rows.get("feature/irons_spellbooks:add_mithril_ore").detail());
        assertEquals(2, WorldgenBalance.catalog(rows).size());
    }

    @Test
    void turningOffWritesCopiesAndTurningOnDeletesThem() throws Exception {
        JsonObject desired = json("{\"structure/irons_spellbooks:citadel\":true,\"feature/irons_spellbooks:add_mithril_ore\":true,"
                + "\"structure/gone:thing\":true}");
        assertTrue(WorldgenBalance.validate(desired).isEmpty());
        WorldgenBalance.Outcome o = WorldgenBalance.reconcile(structures(), modifiers(), pack, desired);
        assertEquals(2, o.written());
        assertEquals(1, o.warnings().size(), o.warnings().toString());

        JsonObject set = PackFiles.read(pack, CITADEL).orElseThrow();
        assertTrue(set.getAsJsonArray("structures").isEmpty());
        assertEquals(199995600, set.getAsJsonObject("placement").get("salt").getAsInt());
        assertEquals(json("{\"type\":\"neoforge:none\"}"), PackFiles.read(pack, ORE).orElseThrow());
        assertTrue(PackFiles.read(pack, SPAWNS).isEmpty());

        Map<String, WorldgenBalance.Row> rows = WorldgenBalance.rows(structures(), modifiers());
        assertEquals(desiredWithout(desired, "structure/gone:thing"), WorldgenBalance.state(rows, pack));
        // saving the same thing again changes nothing
        assertFalse(WorldgenBalance.reconcile(structures(), modifiers(), pack, desired).changed());

        WorldgenBalance.Outcome back = WorldgenBalance.reconcile(structures(), modifiers(), pack,
                json("{\"structure/irons_spellbooks:citadel\":false}"));
        assertEquals(2, back.deleted());
        assertFalse(PackFiles.hasData(pack));
    }

    @Test
    void aCopyThatIsNotOursIsLeftAlone() throws Exception {
        // e.g. a spawn-rule style edit someone put at the modifier path, or a hand-made structure set
        JsonObject custom = structures().get(CITADEL);
        PackFiles.write(pack, CITADEL, custom);
        assertEquals(0, WorldgenBalance.reconcile(structures(), modifiers(), pack, new JsonObject()).deleted());
        assertEquals(custom, PackFiles.read(pack, CITADEL).orElseThrow());
        assertEquals(0, WorldgenBalance.state(WorldgenBalance.rows(structures(), modifiers()), pack).size());
    }

    @Test
    void validateRejectsBadIdsAndValues() {
        List<String> errs = WorldgenBalance.validate(json("{\"nope\":true,\"structure/a:b\":1}"));
        assertEquals(2, errs.size(), errs.toString());
    }

    private static JsonObject desiredWithout(JsonObject desired, String key) {
        JsonObject c = desired.deepCopy();
        c.remove(key);
        return c;
    }
}
