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

/** 총 수치: 카탈로그(원래 값), 사본 = 원본 + 바꾼 값만, 원래대로 = 사본 삭제. */
class GunBalanceTest {
    @TempDir Path pack;

    // Trimmed from the real cgs:weapons/revolver.json (unknown fields kept on purpose).
    static final String REVOLVER = """
            {"general":{"action":"ntgl:gun","rate":10,"gripType":"ntgl:one_handed","maxAmmo":6,"equipTime":0,
              "reloadTime":60,"recoilAngle":-7.0,"spread":1.0,"ammo":["cgs:round_revolver","cgs:round_revolver_piercing"]},
             "ammoData":{"cgs:round_revolver":{"projectile":{"visible":true,"damage":8.0,"speed":20.0}},
               "cgs:round_revolver_piercing":{"ammo":{"type":"ntgl:piercing"},"projectile":{"pierceLevel":2,"damage":12.0}}},
             "modes":{"ntgl:secondary":{"general":{"action":"ntgl:scope"}}},
             "sounds":{"fire":"cgs:gun.revolver.fire"}}""";
    // ntgl shotgun has no equipTime.
    static final String SHOTGUN = """
            {"general":{"action":"ntgl:gun","rate":15,"maxAmmo":5,"reloadTime":10,"recoilAngle":-15.0,"spread":10.0},
             "ammoData":{"ntgl:shotshell":{"projectile":{"damage":1.0}}}}""";
    static final String NOT_A_GUN = "{\"capabilities\":[]}";
    // Trimmed from the real cgs:weapons/nailgun.json: NTGL per-cartridge loading (start → per cycle → end).
    static final String NAILGUN = """
            {"general":{"action":"ntgl:gun","loadingType":"ntgl:per_cartridge","rate":2,"maxAmmo":40,
              "reloadStart":15,"reloadTime":15,"reloadEnd":20,"reloadAmount":10,"spread":3.0},
             "ammoData":{"cgs:nail":{"projectile":{"damage":3.0}}}}""";

    static final ResourceLocation REV = ResourceLocation.parse("cgs:weapons/revolver.json");
    static final ResourceLocation SHOT = ResourceLocation.parse("ntgl:weapons/guns/shotgun.json");
    static final ResourceLocation NAIL = ResourceLocation.parse("cgs:weapons/nailgun.json");

    static JsonObject json(String s) {
        return JsonParser.parseString(s).getAsJsonObject();
    }

    static Map<ResourceLocation, JsonObject> originals() {
        Map<ResourceLocation, JsonObject> m = new LinkedHashMap<>();
        m.put(SHOT, json(SHOTGUN));
        m.put(REV, json(REVOLVER));
        m.put(ResourceLocation.parse("cgs:weapons/odd.json"), json(NOT_A_GUN));
        m.put(NAIL, json(NAILGUN));
        return m;
    }

    @Test
    void idsDropTheJsonSuffix() {
        assertEquals("cgs:weapons/revolver", GunBalance.idOf(REV));
        assertEquals(REV, GunBalance.fileOf("cgs:weapons/revolver"));
        assertNull(GunBalance.fileOf("not an id"));
    }

    @Test
    void catalogShowsOriginalValuesInEditorUnits() {
        JsonArray cat = GunBalance.catalog(originals());
        assertEquals(3, cat.size(), "non-gun file left out");
        JsonObject rev = cat.get(1).getAsJsonObject();   // sorted by id: cgs:…/nailgun, cgs:…/revolver, ntgl:…
        assertEquals("cgs:weapons/revolver", rev.get("id").getAsString());
        assertEquals("cgs", rev.get("mod").getAsString());
        assertEquals("cgs:revolver", rev.get("item").getAsString());
        assertEquals(3.0, rev.get("reloadTime").getAsDouble(), 1e-9);   // 60 ticks
        assertEquals(0.5, rev.get("rate").getAsDouble(), 1e-9);         // 10 ticks
        assertEquals(6, rev.get("maxAmmo").getAsInt());
        assertEquals(0.0, rev.get("equipTime").getAsDouble(), 1e-9);
        assertEquals(-7.0, rev.get("recoilAngle").getAsDouble(), 1e-9);
        JsonArray ammo = rev.getAsJsonArray("ammo");
        assertEquals(2, ammo.size());
        assertEquals("cgs:round_revolver", ammo.get(0).getAsJsonObject().get("id").getAsString());
        assertEquals(8.0, ammo.get(0).getAsJsonObject().get("damage").getAsDouble(), 1e-9);
        JsonObject shot = cat.get(2).getAsJsonObject();
        assertEquals("ntgl:shotgun", shot.get("item").getAsString());
        assertFalse(shot.has("equipTime"), "missing in the mod file: not editable");
    }

    @Test
    void copyKeepsUnknownFieldsAndChangesOnlyEditedValues() throws Exception {
        JsonObject desired = json("{\"cgs:weapons/revolver\":{\"reloadTime\":2.5,\"damage\":{\"cgs:round_revolver\":10}}}");
        GunBalance.Outcome out = GunBalance.reconcile(originals(), pack, desired);
        assertEquals(1, out.written());
        JsonObject copy = PackFiles.read(pack, REV).orElseThrow();
        JsonObject expected = json(REVOLVER);
        expected.getAsJsonObject("general").addProperty("reloadTime", 50);
        expected.getAsJsonObject("ammoData").getAsJsonObject("cgs:round_revolver").getAsJsonObject("projectile")
                .addProperty("damage", 10.0);
        assertEquals(expected, copy, "every other field (sounds, modes, ammo list, piercing) unchanged");
        assertEquals(50, copy.getAsJsonObject("general").get("reloadTime").getAsInt());
    }

    @Test
    void stateReadsBackOnlyTheChangedValues() throws Exception {
        GunBalance.reconcile(originals(), pack, json(
                "{\"cgs:weapons/revolver\":{\"reloadTime\":2.5,\"maxAmmo\":6,\"damage\":{\"cgs:round_revolver_piercing\":20}}}"));
        JsonObject state = GunBalance.state(originals(), pack);
        assertEquals(json("{\"cgs:weapons/revolver\":{\"reloadTime\":2.5,\"damage\":{\"cgs:round_revolver_piercing\":20.0}}}"),
                state, "maxAmmo equal to the original is not an override");
    }

    @Test
    void sameAsOriginalOrMissingMeansNoCopyAndResetDeletesIt() throws Exception {
        GunBalance.reconcile(originals(), pack, json("{\"cgs:weapons/revolver\":{\"rate\":1}}"));
        assertTrue(PackFiles.read(pack, REV).isPresent());
        GunBalance.Outcome out = GunBalance.reconcile(originals(), pack, json("{\"cgs:weapons/revolver\":{\"rate\":0.5}}"));
        assertEquals(1, out.deleted());
        assertTrue(PackFiles.read(pack, REV).isEmpty(), "value back to the original: copy removed");
        GunBalance.reconcile(originals(), pack, json("{\"cgs:weapons/revolver\":{\"rate\":1}}"));
        out = GunBalance.reconcile(originals(), pack, json("{}"));
        assertEquals(1, out.deleted(), "원래대로 (gone from the editor) removes the copy");
        assertEquals(json("{}"), GunBalance.state(originals(), pack));
    }

    @Test
    void unchangedSaveWritesNothing() throws Exception {
        JsonObject d = json("{\"cgs:weapons/revolver\":{\"spread\":2}}");
        assertEquals(1, GunBalance.reconcile(originals(), pack, d).written());
        GunBalance.Outcome again = GunBalance.reconcile(originals(), pack, d);
        assertEquals(0, again.written());
        assertEquals(0, again.deleted());
        assertFalse(again.changed());
    }

    @Test
    void fieldsMissingInTheModFileAndUnknownGunsAreIgnoredWithWarnings() throws Exception {
        GunBalance.Outcome out = GunBalance.reconcile(originals(), pack, json(
                "{\"ntgl:weapons/guns/shotgun\":{\"equipTime\":1,\"damage\":{\"ntgl:nope\":3}},\"gone:weapons/x\":{\"rate\":1}}"));
        assertEquals(0, out.written());
        assertEquals(3, out.warnings().size(), out.warnings().toString());
        assertFalse(Files.exists(pack.resolve("data/ntgl")));
    }

    @Test
    void copiesOfGunsNoLongerInTheCatalogAreLeftAlone() throws Exception {
        ResourceLocation other = ResourceLocation.parse("gone:weapons/x.json");
        PackFiles.write(pack, other, json("{\"general\":{\"rate\":1}}"));
        GunBalance.reconcile(originals(), pack, json("{}"));
        assertTrue(PackFiles.read(pack, other).isPresent());
    }

    @Test
    void validateRejectsBadNumbers() {
        assertEquals(List.of(), GunBalance.validate(json(
                "{\"a:weapons/x\":{\"reloadTime\":0,\"rate\":0.05,\"maxAmmo\":30,\"spread\":0,\"recoilAngle\":-20,\"equipTime\":1,\"damage\":{\"a:b\":0}}}")));
        List<String> errs = GunBalance.validate(json(
                "{\"a:weapons/x\":{\"reloadTime\":-1,\"rate\":0,\"maxAmmo\":2.5,\"spread\":\"x\",\"damage\":{\"a:b\":-1}}}"));
        assertEquals(5, errs.size(), errs.toString());
        assertEquals(1, GunBalance.validate(json("{\"a:weapons/x\":5}")).size());
        assertEquals(1, GunBalance.validate(json("{\"a:weapons/x\":{\"what\":1}}")).size());
    }

    // ----- 장전 방식 (NTGL General.loadingType → LoadingType.getType: 등록된 id 가 아니면 탄창식) -----

    @Test
    void loadingTypeIsReadLikeNtgl() {
        assertEquals(GunBalance.PER_CARTRIDGE, GunBalance.loadingType(json("{\"loadingType\":\"ntgl:per_cartridge\"}")));
        assertEquals(GunBalance.MAGAZINE, GunBalance.loadingType(json("{}")), "missing: NTGL default MAGAZINE");
        assertEquals(GunBalance.MAGAZINE, GunBalance.loadingType(json("{\"loadingType\":\"magazine\"}")));
        assertEquals(GunBalance.MAGAZINE, GunBalance.loadingType(json("{\"loadingType\":\"per_cartridge\"}")),
                "no namespace = minecraft:per_cartridge, not registered -> MAGAZINE (as in NTGL)");
        assertEquals(GunBalance.MAGAZINE, GunBalance.loadingType(json("{\"loadingType\":\"Not An Id\"}")));
        assertEquals(GunBalance.MAGAZINE, GunBalance.loadingType(null));
    }

    @Test
    void catalogShowsLoadingTypeAndStartEndWithNtglDefaults() {
        JsonArray cat = GunBalance.catalog(originals());
        JsonObject nail = cat.get(0).getAsJsonObject();
        assertEquals("cgs:weapons/nailgun", nail.get("id").getAsString());
        assertEquals("per_cartridge", nail.get("loading").getAsString());
        assertEquals(0.75, nail.get("reloadStart").getAsDouble(), 1e-9);
        assertEquals(0.75, nail.get("reloadTime").getAsDouble(), 1e-9);
        assertEquals(1.0, nail.get("reloadEnd").getAsDouble(), 1e-9);
        assertEquals(0, nail.getAsJsonArray("defaulted").size());

        JsonObject rev = cat.get(1).getAsJsonObject();
        assertEquals("magazine", rev.get("loading").getAsString());
        assertEquals(0.0, rev.get("reloadStart").getAsDouble(), 1e-9, "missing in the file: NTGL default 0");
        assertEquals(0.0, rev.get("reloadEnd").getAsDouble(), 1e-9);
        JsonArray defaulted = rev.getAsJsonArray("defaulted");
        assertTrue(defaulted.contains(new com.google.gson.JsonPrimitive("reloadStart")));
        assertTrue(defaulted.contains(new com.google.gson.JsonPrimitive("reloadEnd")));
        assertFalse(cat.get(2).getAsJsonObject().has("equipTime"), "fields without an NTGL default stay non-editable");
    }

    @Test
    void perCartridgeTimesAreWrittenAndOtherFieldsKept() throws Exception {
        GunBalance.reconcile(originals(), pack, json(
                "{\"cgs:weapons/nailgun\":{\"reloadStart\":0.5,\"reloadTime\":0.25,\"reloadEnd\":0}}"));
        JsonObject copy = PackFiles.read(pack, NAIL).orElseThrow();
        JsonObject expected = json(NAILGUN);
        JsonObject g = expected.getAsJsonObject("general");
        g.addProperty("reloadStart", 10);
        g.addProperty("reloadTime", 5);
        g.addProperty("reloadEnd", 0);
        assertEquals(expected, copy, "loadingType, reloadAmount, ammo untouched");
        assertEquals(json("{\"cgs:weapons/nailgun\":{\"reloadStart\":0.5,\"reloadTime\":0.25,\"reloadEnd\":0.0}}"),
                GunBalance.state(originals(), pack));
    }

    @Test
    void startEndMissingInTheOriginalCanBeSetAndGoBackToNoCopy() throws Exception {
        GunBalance.Outcome out = GunBalance.reconcile(originals(), pack, json(
                "{\"cgs:weapons/revolver\":{\"reloadStart\":0.5,\"reloadEnd\":1}}"));
        assertEquals(1, out.written());
        assertEquals(List.of(), out.warnings());
        JsonObject g = PackFiles.read(pack, REV).orElseThrow().getAsJsonObject("general");
        assertEquals(10, g.get("reloadStart").getAsInt());
        assertEquals(20, g.get("reloadEnd").getAsInt());
        assertEquals(json("{\"cgs:weapons/revolver\":{\"reloadStart\":0.5,\"reloadEnd\":1.0}}"),
                GunBalance.state(originals(), pack));

        out = GunBalance.reconcile(originals(), pack, json("{\"cgs:weapons/revolver\":{\"reloadStart\":0,\"reloadEnd\":0}}"));
        assertEquals(1, out.deleted(), "0 = the NTGL default for a missing field: same as the original, no copy");
        assertTrue(PackFiles.read(pack, REV).isEmpty());

        GunBalance.reconcile(originals(), pack, json("{\"cgs:weapons/revolver\":{\"reloadStart\":0,\"rate\":1}}"));
        assertFalse(PackFiles.read(pack, REV).orElseThrow().getAsJsonObject("general").has("reloadStart"),
                "a default value is not written into the copy");
    }

    @Test
    void validateChecksStartAndEnd() {
        assertEquals(List.of(), GunBalance.validate(json("{\"a:weapons/x\":{\"reloadStart\":0,\"reloadEnd\":600}}")));
        assertEquals(2, GunBalance.validate(json("{\"a:weapons/x\":{\"reloadStart\":-0.1,\"reloadEnd\":601}}")).size());
    }

    @Test
    void knownAttachmentOverridesAreNotedInTheCatalog() {
        JsonObject rev = GunBalance.catalog(originals()).get(1).getAsJsonObject();
        String notes = rev.getAsJsonArray("notes").toString();
        assertTrue(notes.contains("cgs:round_belt"), notes);
        assertEquals(0, GunBalance.catalog(originals()).get(2).getAsJsonObject().getAsJsonArray("notes").size());
    }
}
