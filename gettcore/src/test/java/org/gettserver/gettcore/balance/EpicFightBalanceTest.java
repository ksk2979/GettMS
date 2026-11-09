package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Epic Fight 무기 특성: 카탈로그(스타일별 원래 값), 사본 = 원본 + 바꾼 값만, 원래대로 = 사본 삭제. */
class EpicFightBalanceTest {
    @TempDir Path pack;

    // Real epicfight:capabilities/weapons/diamond_dagger.json.
    static final String DAGGER = """
            {"attributes":{"one_hand":{"armor_negation":10.0,"max_strikes":0,"impact":0.8},
              "two_hand":{"armor_negation":10.0,"max_strikes":1,"impact":0.8}},"type":"epicfight:dagger"}""";
    // Real epicfight:capabilities/weapons/bokken.json: type only.
    static final String BOKKEN = "{\"type\":\"epicfight:bokken\"}";
    // Trimmed magistuarmory pike: unknown fields (collider, combos, styles) must survive.
    static final String PIKE = """
            {"collider":{"number":3,"size":[0.6,0.6,1.6],"center":[0.0,0.0,-1.0]},"hit_sound":"epicfight:entity.hit.blade",
             "combos":{"one_hand":["epicfight:biped/combat/spear_onehand_auto"]},"styles":{"default":"one_hand"},
             "attributes":{"common":{"impact":2.6,"max_strikes":2,"speed_bonus":-0.2}},"type":"magistuarmory:pike"}""";
    static final String KEYWORD = "{\"regexes\":[\".*_dagger\"]}";

    static final ResourceLocation DAG = ResourceLocation.parse("epicfight:capabilities/weapons/diamond_dagger.json");
    static final ResourceLocation BOK = ResourceLocation.parse("epicfight:capabilities/weapons/bokken.json");
    static final ResourceLocation PIK = ResourceLocation.parse("magistuarmory:capabilities/weapons/bronze_pike.json");

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static Map<ResourceLocation, JsonObject> originals() {
        Map<ResourceLocation, JsonObject> m = new LinkedHashMap<>();
        m.put(PIK, json(PIKE));
        m.put(DAG, json(DAGGER));
        m.put(BOK, json(BOKKEN));
        m.put(ResourceLocation.parse("epicfight:capabilities/weapons/item_keyword/dagger.json"), json(KEYWORD));
        m.put(ResourceLocation.parse("magistuarmory:capabilities/weapons/types/pike.json"), json("{\"category\":\"x\"}"));
        m.put(ResourceLocation.parse("epicfight:capabilities/armors/x.json"), json("{}"));
        return m;
    }

    @Test
    void idsAreItemIdsAndEpicFightsSkipRuleApplies() {
        assertEquals("epicfight:diamond_dagger", EpicFightBalance.itemOf(DAG));
        assertEquals(DAG, EpicFightBalance.fileOf("epicfight:diamond_dagger"));
        assertNull(EpicFightBalance.itemOf(ResourceLocation.parse("epicfight:capabilities/weapons/item_keyword/sword.json")));
        assertNull(EpicFightBalance.itemOf(ResourceLocation.parse("magistuarmory:capabilities/weapons/types/pike.json")));
        assertNull(EpicFightBalance.itemOf(ResourceLocation.parse("minecraft:capabilities/armors/iron_helmet.json")));
        assertNull(EpicFightBalance.fileOf("not an id"));
    }

    @Test
    void catalogListsStylesWithOriginalValues() {
        JsonArray cat = EpicFightBalance.catalog(originals());
        assertEquals(3, cat.size(), "keyword / type / armor files left out");
        JsonObject bok = cat.get(0).getAsJsonObject();   // sorted by file: epicfight before magistuarmory
        assertEquals("epicfight:bokken", bok.get("id").getAsString());
        assertEquals("epicfight:bokken", bok.get("type").getAsString());
        assertEquals(0, bok.getAsJsonObject("styles").size());
        JsonObject dag = cat.get(1).getAsJsonObject();
        assertEquals("epicfight", dag.get("mod").getAsString());
        assertEquals(json("{\"one_hand\":{\"armor_negation\":10.0,\"impact\":0.8,\"max_strikes\":0.0},"
                + "\"two_hand\":{\"armor_negation\":10.0,\"impact\":0.8,\"max_strikes\":1.0}}"), dag.getAsJsonObject("styles"));
        JsonObject pik = cat.get(2).getAsJsonObject();
        assertEquals("magistuarmory:bronze_pike", pik.get("id").getAsString());
        assertEquals("magistuarmory:pike", pik.get("type").getAsString());
        assertEquals(-0.2, pik.getAsJsonObject("styles").getAsJsonObject("common").get("speed_bonus").getAsDouble(), 1e-9);
    }

    @Test
    void copyKeepsUnknownFieldsAndOtherStyles() throws Exception {
        EpicFightBalance.Outcome out = EpicFightBalance.reconcile(originals(), pack, json(
                "{\"magistuarmory:bronze_pike\":{\"common\":{\"impact\":3.5,\"damage_bonus\":2}},"
                        + "\"epicfight:diamond_dagger\":{\"two_hand\":{\"max_strikes\":3}}}"));
        assertEquals(2, out.written(), out.warnings().toString());
        JsonObject pike = PackFiles.read(pack, PIK).orElseThrow();
        JsonObject expected = json(PIKE);
        JsonObject common = expected.getAsJsonObject("attributes").getAsJsonObject("common");
        common.addProperty("impact", 3.5);
        common.addProperty("damage_bonus", 2.0);
        assertEquals(expected, pike, "collider, combos, styles, type, speed_bonus unchanged");
        JsonObject dagger = PackFiles.read(pack, DAG).orElseThrow();
        JsonObject exp2 = json(DAGGER);
        exp2.getAsJsonObject("attributes").getAsJsonObject("two_hand").addProperty("max_strikes", 3);
        assertEquals(exp2, dagger, "one_hand untouched");
        assertEquals("3", dagger.getAsJsonObject("attributes").getAsJsonObject("two_hand").get("max_strikes").toString(),
                "max_strikes written as a whole number (Epic Fight reads it with getInt)");
    }

    @Test
    void fileWithoutAttributesGetsCommonAndKeepsType() throws Exception {
        EpicFightBalance.reconcile(originals(), pack, json("{\"epicfight:bokken\":{\"common\":{\"impact\":1.5}}}"));
        JsonObject copy = PackFiles.read(pack, BOK).orElseThrow();
        assertEquals(json("{\"type\":\"epicfight:bokken\",\"attributes\":{\"common\":{\"impact\":1.5}}}"), copy);
        assertEquals(json("{\"epicfight:bokken\":{\"common\":{\"impact\":1.5}}}"),
                EpicFightBalance.state(originals(), pack));
    }

    @Test
    void stylesMissingInTheOriginalOtherThanCommonAreSkippedWithAWarning() throws Exception {
        EpicFightBalance.Outcome out = EpicFightBalance.reconcile(originals(), pack, json(
                "{\"epicfight:bokken\":{\"two_hand\":{\"impact\":2}},\"gone:sword\":{\"common\":{\"impact\":1}}}"));
        assertEquals(0, out.written());
        assertEquals(2, out.warnings().size(), out.warnings().toString());
        assertFalse(Files.exists(pack.resolve("data")));
    }

    @Test
    void stateReadsBackOnlyChangedOrAddedValues() throws Exception {
        EpicFightBalance.reconcile(originals(), pack, json(
                "{\"epicfight:diamond_dagger\":{\"one_hand\":{\"impact\":0.8,\"armor_negation\":20,\"speed_bonus\":0.3}}}"));
        assertEquals(json("{\"epicfight:diamond_dagger\":{\"one_hand\":{\"speed_bonus\":0.3,\"armor_negation\":20.0}}}"),
                EpicFightBalance.state(originals(), pack), "impact equal to the original is not an override");
    }

    @Test
    void sameAsOriginalOrResetDeletesTheCopy() throws Exception {
        EpicFightBalance.reconcile(originals(), pack, json("{\"epicfight:diamond_dagger\":{\"one_hand\":{\"impact\":2}}}"));
        assertTrue(PackFiles.read(pack, DAG).isPresent());
        EpicFightBalance.Outcome out = EpicFightBalance.reconcile(originals(), pack,
                json("{\"epicfight:diamond_dagger\":{\"one_hand\":{\"impact\":0.8}}}"));
        assertEquals(1, out.deleted());
        assertTrue(PackFiles.read(pack, DAG).isEmpty());
        EpicFightBalance.reconcile(originals(), pack, json("{\"epicfight:diamond_dagger\":{\"one_hand\":{\"impact\":2}}}"));
        out = EpicFightBalance.reconcile(originals(), pack, json("{}"));
        assertEquals(1, out.deleted(), "원래대로 (gone from the editor) removes the copy");
        EpicFightBalance.Outcome again = EpicFightBalance.reconcile(originals(), pack, json("{}"));
        assertFalse(again.changed());
    }

    @Test
    void copiesOfOtherFilesAreLeftAlone() throws Exception {
        ResourceLocation gun = ResourceLocation.parse("cgs:weapons/revolver.json");
        ResourceLocation other = ResourceLocation.parse("gone:capabilities/weapons/x.json");
        PackFiles.write(pack, gun, json("{\"general\":{\"rate\":1}}"));
        PackFiles.write(pack, other, json("{\"type\":\"epicfight:sword\"}"));
        EpicFightBalance.reconcile(originals(), pack, json("{}"));
        assertTrue(PackFiles.read(pack, gun).isPresent(), "gun copies are not ours to delete");
        assertTrue(PackFiles.read(pack, other).isPresent());
    }

    @Test
    void validateRejectsBadNumbers() {
        assertEquals(List.of(), EpicFightBalance.validate(json(
                "{\"a:b\":{\"common\":{\"damage_bonus\":-1,\"speed_bonus\":-0.4,\"armor_negation\":0,\"impact\":-0.4,\"max_strikes\":0}}}")));
        List<String> errs = EpicFightBalance.validate(json(
                "{\"a:b\":{\"common\":{\"armor_negation\":-5,\"max_strikes\":1.5,\"impact\":\"x\",\"speed_bonus\":9,\"what\":1},"
                        + "\"Bad Style\":{},\"two_hand\":3}}"));
        assertEquals(7, errs.size(), errs.toString());
        assertEquals(1, EpicFightBalance.validate(json("{\"a:b\":5}")).size());
    }
}
