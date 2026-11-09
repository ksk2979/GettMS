package org.gettserver.gettcore.balance;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 모드 장비 제작법 막기: 결과 아이템 읽기·장비 분류·"항상 거짓" 조건을 넣은 사본. */
class RecipeBalanceTest {
    @TempDir Path pack;

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    // ---- 결과 아이템 (실제 모드 jar 에 있는 형식들) ----

    @Test
    void resultOfShapedAndSmithing() {
        assertEquals(List.of("magistuarmory:armet"), RecipeBalance.resultIds(json(
                "{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"#\"],\"key\":{},\"result\":{\"count\":1,\"id\":\"magistuarmory:armet\"}}")));
        assertEquals(List.of("wom:antitheus"), RecipeBalance.resultIds(json(
                "{\"type\":\"minecraft:smithing_transform\",\"base\":{},\"addition\":{},\"template\":{},\"result\":{\"id\":\"wom:antitheus\"}}")));
    }

    @Test
    void resultAsLegacyItemKeyOrString() {
        assertEquals(List.of("a:b"), RecipeBalance.resultIds(json("{\"result\":{\"item\":\"a:b\"}}")));
        assertEquals(List.of("cgs:lead_ingot"), RecipeBalance.resultIds(json("{\"result\":\"cgs:lead_ingot\"}")));
    }

    @Test
    void createResultsListSkipsFluids() {
        assertEquals(List.of("cgs:round_revolver", "cgs:nail"), RecipeBalance.resultIds(json(
                "{\"type\":\"create:sequenced_assembly\",\"results\":[{\"id\":\"cgs:round_revolver\",\"count\":4},"
                        + "{\"id\":\"cgs:nail\",\"chance\":0.1},{\"id\":\"minecraft:lava\",\"amount\":250}]}")));
    }

    @Test
    void farmersDelightCuttingItemObjectOrString() {
        assertEquals(List.of("x:a", "x:b"), RecipeBalance.resultIds(json(
                "{\"type\":\"farmersdelight:cutting\",\"result\":[{\"item\":{\"id\":\"x:a\",\"count\":2}},{\"item\":\"x:b\",\"chance\":0.5}]}")));
    }

    @Test
    void noResult() {
        assertEquals(List.of(), RecipeBalance.resultIds(json("{\"type\":\"botanypots:soil\"}")));
        assertEquals(List.of(), RecipeBalance.resultIds(json("{\"result\":{\"basePredicate\":{},\"count\":2}}")));
        assertEquals(List.of(), RecipeBalance.resultIds(json("{\"result\":5}")));
    }

    // ---- 장비 판정 ----

    static final Function<String, String> KINDS = id -> switch (id) {
        case "magistuarmory:armet", "minecraft:iron_helmet" -> RecipeBalance.ARMOR;
        case "ntgl:pistol10mm" -> RecipeBalance.GUN;
        case "wom:solar", "epicfight:netherite_dagger" -> RecipeBalance.WEAPON;
        default -> null;
    };

    @Test
    void equipmentResultIgnoresVanillaAndNonEquipment() {
        assertEquals("magistuarmory:armet", RecipeBalance.equipmentResult(List.of("magistuarmory:armet"), KINDS));
        assertNull(RecipeBalance.equipmentResult(List.of("minecraft:iron_helmet"), KINDS));
        assertNull(RecipeBalance.equipmentResult(List.of("cgs:lead_ingot"), KINDS));
        assertEquals("wom:solar", RecipeBalance.equipmentResult(List.of("cgs:nail", "wom:solar"), KINDS));
        assertNull(RecipeBalance.equipmentResult(List.of("not an id"), KINDS));
    }

    @Test
    void classifyFromItemFacts() {
        // gun, shieldItem, shieldAbility, armorItem, ranged, attackDamage, armorAttr
        assertEquals(RecipeBalance.GUN, RecipeBalance.classify(new RecipeBalance.Facts(true, false, false, false, false, true, false)));
        assertEquals(RecipeBalance.SHIELD, RecipeBalance.classify(new RecipeBalance.Facts(false, true, true, false, false, true, true)));
        assertEquals(RecipeBalance.ARMOR, RecipeBalance.classify(new RecipeBalance.Facts(false, false, false, true, false, false, true)));
        assertEquals(RecipeBalance.WEAPON, RecipeBalance.classify(new RecipeBalance.Facts(false, false, false, false, true, false, false)));
        assertEquals(RecipeBalance.WEAPON, RecipeBalance.classify(new RecipeBalance.Facts(false, false, false, false, false, true, false)));
        // Epic Knights blades/polearms can block (shield_block ability) but are weapons
        assertEquals(RecipeBalance.WEAPON, RecipeBalance.classify(new RecipeBalance.Facts(false, false, true, false, false, true, false)));
        assertEquals(RecipeBalance.SHIELD, RecipeBalance.classify(new RecipeBalance.Facts(false, false, true, false, false, false, false)));
        assertEquals(RecipeBalance.OTHER, RecipeBalance.classify(new RecipeBalance.Facts(false, false, false, false, false, false, true)));
        // a tool without attack damage (and nothing else) is not equipment
        assertNull(RecipeBalance.classify(new RecipeBalance.Facts(false, false, false, false, false, false, false)));
    }

    // ---- 막는 사본 ----

    @Test
    void disabledCopyKeepsEverythingAndAddsFalseCondition() {
        JsonObject orig = json("{\"type\":\"minecraft:crafting_shaped\",\"pattern\":[\"##\"],\"key\":{\"#\":{\"item\":\"x:y\"}},"
                + "\"result\":{\"id\":\"magistuarmory:armet\",\"count\":1}}");
        JsonObject before = orig.deepCopy();
        JsonObject copy = RecipeBalance.disabledCopy(orig);
        assertEquals(before, orig, "original untouched");
        JsonObject expected = before.deepCopy();
        expected.add("neoforge:conditions", JsonParser.parseString("[{\"type\":\"neoforge:false\"}]"));
        assertEquals(expected, copy);
        assertTrue(RecipeBalance.isDisabled(copy));
        assertFalse(RecipeBalance.isDisabled(orig));
    }

    @Test
    void disabledCopyKeepsExistingConditions() {
        JsonObject orig = json("{\"neoforge:conditions\":[{\"type\":\"neoforge:mod_loaded\",\"modid\":\"create\"}],"
                + "\"fabric:load_conditions\":[],\"result\":{\"id\":\"a:b\"}}");
        JsonObject copy = RecipeBalance.disabledCopy(orig);
        JsonArray c = copy.getAsJsonArray("neoforge:conditions");
        assertEquals(2, c.size());
        assertEquals("neoforge:mod_loaded", c.get(0).getAsJsonObject().get("type").getAsString());
        assertEquals("neoforge:false", c.get(1).getAsJsonObject().get("type").getAsString());
        assertTrue(copy.has("fabric:load_conditions"));
        assertEquals(1, orig.getAsJsonArray("neoforge:conditions").size());
        assertEquals(copy, RecipeBalance.disabledCopy(copy), "no second false condition");
    }

    @Test
    void idAndFileRoundTrip() {
        ResourceLocation f = ResourceLocation.parse("cgs:recipe/mechanical_crafting/revolver.json");
        assertEquals("cgs:mechanical_crafting/revolver", RecipeBalance.idOf(f));
        assertEquals(f, RecipeBalance.fileOf("cgs:mechanical_crafting/revolver"));
        assertNull(RecipeBalance.idOf(ResourceLocation.parse("cgs:weapons/revolver.json")));
        assertNull(RecipeBalance.fileOf("bad id!"));
    }

    // ---- 목록 · 저장 ----

    static final ResourceLocation ARMET = ResourceLocation.parse("magistuarmory:recipe/armet.json");
    static final ResourceLocation PISTOL = ResourceLocation.parse("minecraft:recipe/pistol10mm.json");
    static final ResourceLocation HELMET = ResourceLocation.parse("minecraft:recipe/iron_helmet.json");
    static final ResourceLocation INGOT = ResourceLocation.parse("cgs:recipe/lead_ingot.json");

    static Map<ResourceLocation, JsonObject> originals() {
        return Map.of(
                ARMET, json("{\"type\":\"minecraft:crafting_shaped\",\"result\":{\"id\":\"magistuarmory:armet\"}}"),
                PISTOL, json("{\"type\":\"ntgl:workbench\",\"result\":{\"id\":\"ntgl:pistol10mm\"}}"),
                HELMET, json("{\"type\":\"minecraft:crafting_shaped\",\"result\":{\"id\":\"minecraft:iron_helmet\"}}"),
                INGOT, json("{\"type\":\"minecraft:crafting_shapeless\",\"result\":{\"id\":\"cgs:lead_ingot\"}}"));
    }

    @Test
    void catalogListsModEquipmentOnly() {
        JsonArray cat = RecipeBalance.catalog(RecipeBalance.rows(originals(), KINDS, pack));
        assertEquals(2, cat.size());
        JsonObject first = cat.get(0).getAsJsonObject();
        assertEquals("magistuarmory:armet", first.get("id").getAsString());
        assertEquals("magistuarmory:armet", first.get("item").getAsString());
        assertEquals("magistuarmory", first.get("mod").getAsString());
        assertEquals(RecipeBalance.ARMOR, first.get("kind").getAsString());
        assertEquals("minecraft:crafting_shaped", first.get("type").getAsString());
        JsonObject gun = cat.get(1).getAsJsonObject();
        assertEquals("minecraft:pistol10mm", gun.get("id").getAsString());
        assertEquals("ntgl", gun.get("mod").getAsString());
        assertEquals(RecipeBalance.GUN, gun.get("kind").getAsString());
    }

    @Test
    void reconcileWritesAndDeletesCopies() throws Exception {
        Map<ResourceLocation, JsonObject> orig = originals();
        RecipeBalance.Outcome o = RecipeBalance.reconcile(orig, KINDS, pack,
                json("{\"magistuarmory:armet\":true,\"minecraft:pistol10mm\":false}"));
        assertEquals(1, o.written());
        assertEquals(0, o.deleted());
        assertEquals(RecipeBalance.disabledCopy(orig.get(ARMET)), PackFiles.read(pack, ARMET).orElseThrow());
        assertTrue(PackFiles.read(pack, PISTOL).isEmpty());
        assertEquals(json("{\"magistuarmory:armet\":true}"), RecipeBalance.state(orig, KINDS, pack));

        // same again: nothing changes
        assertFalse(RecipeBalance.reconcile(orig, KINDS, pack, json("{\"magistuarmory:armet\":true}")).changed());

        // unblock = delete the copy
        RecipeBalance.Outcome back = RecipeBalance.reconcile(orig, KINDS, pack, json("{}"));
        assertEquals(1, back.deleted());
        assertTrue(PackFiles.read(pack, ARMET).isEmpty());
        assertEquals(json("{}"), RecipeBalance.state(orig, KINDS, pack));
    }

    @Test
    void reconcileRefusesVanillaAndUnknownRecipes() throws Exception {
        RecipeBalance.Outcome o = RecipeBalance.reconcile(originals(), KINDS, pack,
                json("{\"minecraft:iron_helmet\":true,\"cgs:lead_ingot\":true,\"gone:sword\":true}"));
        assertEquals(0, o.written());
        assertEquals(3, o.warnings().size());
        assertTrue(PackFiles.read(pack, HELMET).isEmpty());
        assertTrue(PackFiles.read(pack, INGOT).isEmpty());
    }

    @Test
    void foreignCopyIsNotDeleted() throws Exception {
        JsonObject edited = json("{\"type\":\"minecraft:crafting_shaped\",\"result\":{\"id\":\"magistuarmory:armet\",\"count\":2}}");
        PackFiles.write(pack, ARMET, edited);
        RecipeBalance.Outcome o = RecipeBalance.reconcile(originals(), KINDS, pack, json("{}"));
        assertEquals(0, o.deleted());
        assertEquals(edited, PackFiles.read(pack, ARMET).orElseThrow());
    }

    @Test
    void blockedNonEquipmentCopyStillListedSoItCanBeUnblocked() throws Exception {
        Map<ResourceLocation, JsonObject> orig = originals();
        PackFiles.write(pack, INGOT, RecipeBalance.disabledCopy(orig.get(INGOT)));
        JsonArray cat = RecipeBalance.catalog(RecipeBalance.rows(orig, KINDS, pack));
        assertEquals(3, cat.size());
        assertEquals(json("{\"cgs:lead_ingot\":true}"), RecipeBalance.state(orig, KINDS, pack));
        assertEquals(1, RecipeBalance.reconcile(orig, KINDS, pack, json("{}")).deleted());
    }

    @Test
    void validateNeedsBooleans() {
        assertEquals(List.of(), RecipeBalance.validate(json("{\"a:b\":true,\"c:d\":false}")));
        assertEquals(1, RecipeBalance.validate(json("{\"a:b\":1}")).size());
        assertEquals(1, RecipeBalance.validate(json("{\"bad id!\":true}")).size());
    }

    @Test
    void recipeCopyWithoutOriginalIsAnOrphan() throws Exception {
        PackFiles.write(pack, ARMET, RecipeBalance.disabledCopy(originals().get(ARMET)));
        PackFiles.write(pack, PISTOL, RecipeBalance.disabledCopy(originals().get(PISTOL)));
        List<BalanceOrphans.Orphan> found = BalanceOrphans.find(pack,
                Map.of(BalanceService.RECIPES, Set.of(PISTOL)), e -> true);
        assertEquals(1, found.size());
        assertEquals(BalanceService.RECIPES, found.get(0).kind());
        assertEquals("magistuarmory:armet", found.get(0).id());
        assertEquals(List.of(), BalanceOrphans.validate(JsonParser.parseString(
                "[{\"kind\":\"recipes\",\"id\":\"magistuarmory:armet\"}]")));
        JsonArray req = JsonParser.parseString("[{\"kind\":\"recipes\",\"id\":\"magistuarmory:armet\"}]").getAsJsonArray();
        assertEquals(1, BalanceOrphans.remove(pack, found, req));
        assertTrue(PackFiles.read(pack, ARMET).isEmpty());
    }
}
